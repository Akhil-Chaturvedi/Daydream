package com.bytesmith.daydream

import android.annotation.SuppressLint
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import android.graphics.drawable.Icon

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class NotificationService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var lastSentMetadataIntent: Intent? = null

    private val mediaSessionCheckRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkAndResetSongCountIfNeeded()
            checkMediaSessions()
            handler.postDelayed(this, MEDIA_SESSION_CHECK_INTERVAL)
        }
    }

    companion object {
        private const val TAG = "NotificationService"
        const val UPDATE_NOTIFICATIONS_ACTION = "com.bytesmith.daydream.UPDATE_NOTIFICATIONS"
        const val MEDIA_INFO_REQUEST_ACTION = "com.bytesmith.daydream.REQUEST_MEDIA_INFO"
        const val SONG_NAME_UPDATED_ACTION = "com.bytesmith.daydream.SONG_NAME_UPDATED"
        const val MEDIA_METADATA_UPDATED_ACTION = "com.bytesmith.daydream.MEDIA_METADATA_UPDATED"
        private const val PREFS_NAME = "MediaPlaybackPrefs"
        private const val PREF_SONG_COUNT = "songCount"
        private const val PREF_LAST_RESET_TIMESTAMP = "lastResetTimestamp"
        private const val PREF_TRACKING_SONG_ID = "trackingSongId"
        private const val PREF_TRACKING_SONG_START_TIME = "trackingSongStartTime"
        private const val MIN_PLAYBACK_DURATION_MS = 30000L
        private const val MAX_PLAYBACK_DURATION_MS = 600000L
        private const val MEDIA_SESSION_CHECK_INTERVAL = 5000L
        private const val EXCLUDED_PACKAGE = "com.miui.securitycore"
        private val activeNotificationsMap = ConcurrentHashMap<String, MutableSet<String>>()

        data class MediaInfo(
            val cleanedSongString: String?,
            val albumArt: Bitmap?,
            val cacheKey: String?
        )

        @Volatile
        var lastKnownMediaInfo: MediaInfo? = null

        @Volatile
        private var mediaNotificationKey: String? = null
        @Volatile
        private var currentlyPlayingMediaPackage: String? = null
        private val BRACKET_PATTERN: Pattern = Pattern.compile("\\(.*?\\)|\\[.*?]")
        private val MULTIPLE_SPACE_PATTERN: Pattern = Pattern.compile("\\s{2,}")
        private val WHITESPACE_PATTERN: Pattern = Pattern.compile("\\s+")
        private val PUNCTUATION_PATTERN: Pattern = Pattern.compile("[\\p{Punct}]")
        private var instanceRef: WeakReference<NotificationService>? = null

        @Volatile
        private var isDreamActive = false

        // --- THE ROBUSTNESS FIX ---
        @JvmStatic
        fun setDreamActive(isActive: Boolean) {
            isDreamActive = isActive
            Log.d(TAG, "Dream active status set to: $isDreamActive")

            // When the dream becomes active, proactively send it the last known
            // media state and a signal to update notification icons. This ensures
            // the UI is populated immediately upon activation and helps it recover
            // from any transient state loss during orientation changes.
            if (isActive) {
                instanceRef?.get()?.apply {
                    lastSentMetadataIntent?.let {
                        sendBroadcast(it)
                        Log.d(TAG, "Proactively sent last known metadata to newly active dream.")
                    }
                    sendBroadcast(Intent(UPDATE_NOTIFICATIONS_ACTION))
                    broadcastCurrentSongCount()
                }
            }
        }

        @JvmStatic
        fun getPackagesForIconDisplay(): Set<String> {
            val packagesToDisplay = mutableSetOf<String>()
            val currentMediaPkg = currentlyPlayingMediaPackage
            activeNotificationsMap.entries.forEach { (packageName, keys) ->
                if (packageName.equals(EXCLUDED_PACKAGE, ignoreCase = true)) return@forEach
                if (packageName == currentMediaPkg) {
                    val instance = instanceRef?.get()
                    val hasNonMediaNotification = instance?.activeNotifications?.any { sbn ->
                        packageName == sbn.packageName && keys.contains(sbn.key) && !instance.isMediaNotification(sbn)
                    } ?: false
                    if (hasNonMediaNotification) {
                        packagesToDisplay.add(packageName)
                    }
                } else if (keys.isNotEmpty()) {
                    packagesToDisplay.add(packageName)
                }
            }
            Log.d(TAG, "Final packages for icon display: ${packagesToDisplay.size}")
            return packagesToDisplay
        }

        @JvmStatic
        fun getSmallIconForPackage(context: Context, packageName: String): Drawable? {
            val instance = instanceRef?.get() ?: return null
            val sbn = instance.activeNotifications.firstOrNull { it.packageName == packageName }
            return sbn?.notification?.smallIcon?.loadDrawable(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(mediaInfoRequestReceiver, IntentFilter(MEDIA_INFO_REQUEST_ACTION))
        startMediaSessionChecks()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMediaSessionChecks()
        try {
            unregisterReceiver(mediaInfoRequestReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        maybeCountAndClearTrackingSong(System.currentTimeMillis())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instanceRef = WeakReference(this)
        Log.d(TAG, "NotificationListener connected")
        activeNotificationsMap.clear()
        currentlyPlayingMediaPackage = null
        mediaNotificationKey = null
        clearSongTrackingState()
        activeNotifications?.forEach { sbn -> onNotificationPosted(sbn, isInternalCall = true) }
        checkMediaSessions()
        sendUpdateBroadcast()
        if (currentlyPlayingMediaPackage != null && lastTitle != null) {
            sendSongNameBroadcast(buildSongString(lastTitle, lastArtist))
        }
        broadcastCurrentSongCount()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instanceRef = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        onNotificationPosted(sbn, isInternalCall = false)
    }

    private fun onNotificationPosted(sbn: StatusBarNotification?, isInternalCall: Boolean) {
        sbn ?: return
        if (sbn.packageName == EXCLUDED_PACKAGE) return
        val packageName = sbn.packageName
        val key = sbn.key
        activeNotificationsMap.computeIfAbsent(packageName) { ConcurrentHashMap.newKeySet() }.add(key)

        if (isMediaNotification(sbn)) {
            Log.d(TAG, "Posted notification IS a media notification: $key")
            if (packageName != currentlyPlayingMediaPackage) {
                checkMediaSessions()
            }
        }

        if (!isInternalCall) {
            sendUpdateBroadcast()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        onNotificationRemoved(sbn, isInternalCall = false)
    }

    private fun onNotificationRemoved(sbn: StatusBarNotification?, isInternalCall: Boolean) {
        sbn ?: return
        val packageName = sbn.packageName
        val key = sbn.key
        var changed = false
        activeNotificationsMap[packageName]?.let { keys ->
            if (keys.remove(key)) {
                changed = true
                if (keys.isEmpty()) {
                    activeNotificationsMap.remove(packageName)
                }
            }
        }
        if (key == mediaNotificationKey) {
            Log.d(TAG, "Media notification was removed: $key")
            mediaNotificationKey = null
            if (packageName == currentlyPlayingMediaPackage) {
                currentlyPlayingMediaPackage = null
                lastTitle = null
                lastArtist = null
                clearMetadata()
            }
            changed = true
        }
        if (changed && !isInternalCall) {
            sendUpdateBroadcast()
        }
    }

    private fun checkMediaSessions() {
        val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        mediaSessionManager ?: return
        val componentName = ComponentName(this, NotificationService::class.java)
        val controllers = try {
            mediaSessionManager.getActiveSessions(componentName)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception checking media sessions", e)
            return
        }
        if (controllers.isNullOrEmpty()) {
            if (currentlyPlayingMediaPackage != null) {
                Log.d(TAG, "Clearing currently playing package as no sessions are active.")
                currentlyPlayingMediaPackage = null
                clearMetadata()
                sendUpdateBroadcast()
            }
            return
        }
        val activeController = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.first()
        val controllerPackage = activeController.packageName
        val metadata = activeController.metadata
        val playbackState = activeController.playbackState

        if (playbackState?.state == PlaybackState.STATE_PLAYING) {
            if (controllerPackage != currentlyPlayingMediaPackage) {
                currentlyPlayingMediaPackage = controllerPackage
                sendUpdateBroadcast()
            }
        } else if (controllerPackage == currentlyPlayingMediaPackage) {
            // Keep the package name briefly if paused/stopped, until metadata disappears,
        }

        handlePlaybackStateChange(playbackState, controllerPackage, metadata)
    }

    private fun handlePlaybackStateChange(state: PlaybackState?, packageName: String, metadata: MediaMetadata?) {
        val currentTime = System.currentTimeMillis()
        val currentSongId = metadata?.let { buildSongId(it) }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val trackedSongId = prefs.getString(PREF_TRACKING_SONG_ID, null)

        when (state?.state) {
            PlaybackState.STATE_PLAYING -> {
                currentlyPlayingMediaPackage = packageName
                findAndSetMediaNotificationKey(packageName)

                if (currentSongId != null) {
                    if (currentSongId != trackedSongId) {
                        maybeCountAndClearTrackingSong(currentTime)
                        startTrackingSong(currentSongId, currentTime)
                    }

                    lastTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    lastArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

                    var albumArt: Bitmap? = null

                    if (metadata.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ART)) {
                        albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        Log.d(TAG, "Extracted art via METADATA_KEY_ALBUM_ART")
                    }
                    if (albumArt == null && metadata.containsKey(MediaMetadata.METADATA_KEY_DISPLAY_ICON)) {
                        albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                        Log.d(TAG, "Extracted art via METADATA_KEY_DISPLAY_ICON")
                    }

                    if (albumArt == null) {
                        val sbn = activeNotifications.firstOrNull { it.key == mediaNotificationKey }
                        if (sbn != null) {

                            val iconParcelable = getParcelableCompat(
                                sbn.notification?.extras ?: Bundle.EMPTY,
                                Notification.EXTRA_LARGE_ICON_BIG,
                                Parcelable::class.java
                            ) ?: getParcelableCompat(
                                sbn.notification?.extras ?: Bundle.EMPTY,
                                Notification.EXTRA_LARGE_ICON,
                                Parcelable::class.java
                            )

                            when (iconParcelable) {
                                is Bitmap -> {
                                    albumArt = iconParcelable
                                    Log.d(TAG, "Extracted art via EXTRA_LARGE_ICON (was a Bitmap)")
                                }
                                is Icon -> {
                                    albumArt = drawableToBitmap(iconParcelable.loadDrawable(this))
                                    Log.d(TAG, "Extracted art via EXTRA_LARGE_ICON (was an Icon, converted successfully)")
                                }
                                else -> {
                                    Log.d(TAG, "Icon object found but was neither Bitmap nor Icon.")
                                }
                            }
                        }
                    }
                    Log.d(TAG, "Final check: Album art extracted successfully? ${albumArt != null}")

                    val cacheKey = "${lastTitle}_${lastArtist}_${album}_${duration}".replace(Regex("[^a-zA-Z0-9_]"), "")
                    sendMetadataBroadcast(lastTitle, lastArtist, albumArt, cacheKey)
                }
                sendUpdateBroadcast()
            }

            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> {
                maybeCountAndClearTrackingSong(currentTime)
                if (packageName == currentlyPlayingMediaPackage) {
                    currentlyPlayingMediaPackage = null
                }
                sendUpdateBroadcast()
            }
            else -> { // Null state or other states
                maybeCountAndClearTrackingSong(currentTime)
                if (packageName == currentlyPlayingMediaPackage) {
                    currentlyPlayingMediaPackage = null
                    clearMetadata()
                }
                sendUpdateBroadcast()
            }
        }
    }

    private fun <T : Parcelable> getParcelableCompat(bundle: Bundle, key: String, clazz: Class<T>): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelable(key, clazz)
        } else {
            @Suppress("DEPRECATION", "UNCHECKED_CAST")
            bundle.getParcelable(key) as? T
        }
    }

    private fun sendMetadataBroadcast(title: String?, artist: String?, art: Bitmap?, cacheKey: String?) {
        val songString = buildSongString(title, artist)
        lastKnownMediaInfo = MediaInfo(songString, art, cacheKey)

        val intent = Intent(MEDIA_METADATA_UPDATED_ACTION).apply {
            val bundle = Bundle().apply {
                putString("cleanedSongString", songString)
                putString("cacheKey", cacheKey)
                putParcelable("albumArt", art)
            }
            putExtras(bundle)
        }

        // Always update the last sent intent, but only broadcast if dream is active
        lastSentMetadataIntent = intent
        if (!isDreamActive) return

        sendBroadcast(intent)
        Log.d(TAG, "Sent full metadata broadcast for cache key: $cacheKey")
    }

    private fun clearMetadata() {
        lastKnownMediaInfo = null
        val intent = Intent(MEDIA_METADATA_UPDATED_ACTION)
        lastSentMetadataIntent = intent // Store the clear intent as the last known state
        if (isDreamActive) {
            sendBroadcast(intent)
        }
    }

    private val mediaInfoRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MEDIA_INFO_REQUEST_ACTION) {
                lastSentMetadataIntent?.let { sendBroadcast(it) }
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        drawable ?: return null
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }

        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            return null
        }

        val bitmap = try {
            Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create bitmap from drawable dimensions.", e)
            return null
        }

        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun startMediaSessionChecks() {
        if (isRunning) return
        isRunning = true
        handler.post(mediaSessionCheckRunnable)
    }

    private fun stopMediaSessionChecks() {
        isRunning = false
        handler.removeCallbacks(mediaSessionCheckRunnable)
    }

    private fun sendUpdateBroadcast() {
        if (!isDreamActive) return

        sendBroadcast(Intent(UPDATE_NOTIFICATIONS_ACTION))
        Log.d(TAG, "Broadcast sent for icon update because dream is active.")
        broadcastCurrentSongCount()
    }

    private fun broadcastCurrentSongCount() {
        val currentCount = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_SONG_COUNT, 0)
        DreamService.updateSongCount(currentCount)
    }

    private fun sendSongNameBroadcast(songName: String?) {
        val intent = Intent(SONG_NAME_UPDATED_ACTION).apply {
            putExtra("songName", songName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Sent song name broadcast: ${songName?.replace("\n", " | ") ?: "null"}")
    }

    private fun getStartOfDayTimestamp(timestamp: Long): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Synchronized
    private fun checkAndResetSongCountIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastResetTimestamp = prefs.getLong(PREF_LAST_RESET_TIMESTAMP, 0)
        val currentTime = System.currentTimeMillis()

        if (getStartOfDayTimestamp(currentTime) > getStartOfDayTimestamp(lastResetTimestamp)) {
            Log.d(TAG, "New day detected by periodic check. Resetting song count.")
            prefs.edit {
                putInt(PREF_SONG_COUNT, 0)
                putLong(PREF_LAST_RESET_TIMESTAMP, currentTime)
            }
            broadcastCurrentSongCount()
        }
    }

    @Synchronized
    private fun incrementAndBroadcastSongCount() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        checkAndResetSongCountIfNeeded()

        var currentCount = prefs.getInt(PREF_SONG_COUNT, 0)
        currentCount++
        Log.d(TAG, "Incrementing song count to: $currentCount")

        prefs.edit {
            putInt(PREF_SONG_COUNT, currentCount)
        }
        broadcastCurrentSongCount()
    }

    @Synchronized
    private fun maybeCountAndClearTrackingSong(endTime: Long) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val startTime = prefs.getLong(PREF_TRACKING_SONG_START_TIME, -1)
        val songId = prefs.getString(PREF_TRACKING_SONG_ID, null)
        if (startTime != -1L && songId != null) {
            val duration = endTime - startTime
            if (duration in MIN_PLAYBACK_DURATION_MS..MAX_PLAYBACK_DURATION_MS) {
                Log.d(TAG, "Duration valid. Incrementing count.")
                incrementAndBroadcastSongCount()
            }
        }
        clearSongTrackingState(prefs)
    }

    @Synchronized
    private fun startTrackingSong(songId: String, startTime: Long) {
        Log.d(TAG, "Starting to track song [$songId] at $startTime")
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(PREF_TRACKING_SONG_ID, songId)
            putLong(PREF_TRACKING_SONG_START_TIME, startTime)
        }
    }

    @Synchronized
    private fun clearSongTrackingState(prefs: SharedPreferences? = null) {
        val editor = (prefs ?: getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)).edit()
        editor.remove(PREF_TRACKING_SONG_ID)
        editor.remove(PREF_TRACKING_SONG_START_TIME)
        editor.apply()
    }

    private fun buildSongId(metadata: MediaMetadata): String {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim() ?: "UNKNOWN_TITLE"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim() ?: "UNKNOWN_ARTIST"
        return "$title||$artist"
    }

    private fun buildSongString(rawTitle: String?, rawArtist: String?): String? {
        val cleanedTitle = cleanSongTitle(rawTitle, rawArtist)
        val builder = StringBuilder()
        if (!cleanedTitle.isNullOrBlank()) {
            builder.append(cleanedTitle)
        }
        if (!rawArtist.isNullOrBlank()) {
            if (builder.isNotEmpty()) builder.append("\n")
            builder.append(rawArtist.trim())
        }
        return if (builder.isNotEmpty()) builder.toString() else null
    }

    private fun cleanSongTitle(rawTitle: String?, rawArtist: String?): String? {
        if (rawTitle.isNullOrBlank()) return rawTitle
        var cleanedTitle = rawTitle
        val suffixIndex = cleanedTitle.indexOf(" - ")
        if (suffixIndex != -1) {
            cleanedTitle = cleanedTitle.substring(0, suffixIndex).trim()
        }
        cleanedTitle = BRACKET_PATTERN.matcher(cleanedTitle).replaceAll("").trim()
        cleanedTitle = MULTIPLE_SPACE_PATTERN.matcher(cleanedTitle).replaceAll(" ").trim()
        if (!rawArtist.isNullOrBlank() && cleanedTitle.isNotEmpty()) {
            val lowerCaseArtistWords = WHITESPACE_PATTERN.split(rawArtist)
                .map { PUNCTUATION_PATTERN.matcher(it).replaceAll("").lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            if (lowerCaseArtistWords.isNotEmpty()) {
                cleanedTitle = WHITESPACE_PATTERN.split(cleanedTitle)
                    .filter { !lowerCaseArtistWords.contains(it.lowercase()) }
                    .joinToString(" ")
            }
        }
        return cleanedTitle.trim()
    }

    private fun findAndSetMediaNotificationKey(targetPackageName: String) {
        mediaNotificationKey = activeNotifications.firstOrNull { sbn ->
            targetPackageName == sbn.packageName && isMediaNotification(sbn)
        }?.key
        if (mediaNotificationKey == null) {
            Log.w(TAG, "Could not find an active media notification key for package: $targetPackageName")
        }
    }

    @SuppressLint("NewApi")
    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        if (notification.extras.containsKey("android.mediaSession")) return true
        if (notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true
        return notification.actions?.any {
            val title = it.title.toString()
            title.equals("Play", ignoreCase = true) || title.equals("Pause", ignoreCase = true) ||
                    title.equals("Stop", ignoreCase = true) || title.equals("Previous", ignoreCase = true) ||
                    title.equals("Next", ignoreCase = true)
        } ?: false
    }
}