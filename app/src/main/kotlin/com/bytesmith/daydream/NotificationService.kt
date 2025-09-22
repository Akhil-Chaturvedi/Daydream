package com.bytesmith.daydream
import android.annotation.SuppressLint
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
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
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class NotificationService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var lastTitle: String? = null
    private var lastArtist: String? = null
    // This object expression creates the Runnable
    private val mediaSessionCheckRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkMediaSessions()
            // Reschedule to keep checking
            handler.postDelayed(this, MEDIA_SESSION_CHECK_INTERVAL)
        }
    }
    // Companion object holds all the static members from the Java class
    companion object {
        private const val TAG = "NotificationService"
        const val UPDATE_NOTIFICATIONS_ACTION = "com.bytesmith.daydream.UPDATE_NOTIFICATIONS"
        const val MEDIA_INFO_REQUEST_ACTION = "com.bytesmith.daydream.REQUEST_MEDIA_INFO"
        const val SONG_NAME_UPDATED_ACTION = "com.bytesmith.daydream.SONG_NAME_UPDATED"
        private const val PREFS_NAME = "MediaPlaybackPrefs"
        private const val PREF_SONG_COUNT = "songCount"
        private const val PREF_LAST_RESET_TIMESTAMP = "lastResetTimestamp"
        private const val PREF_TRACKING_SONG_ID = "trackingSongId"
        private const val PREF_TRACKING_SONG_START_TIME = "trackingSongStartTime"
        private const val MIN_PLAYBACK_DURATION_MS = 30000L // 30 seconds
        private const val MAX_PLAYBACK_DURATION_MS = 600000L // 10 minutes
        private const val MEDIA_SESSION_CHECK_INTERVAL = 5000L
        private const val EXCLUDED_PACKAGE = "com.miui.securitycore"
        private val activeNotificationsMap = ConcurrentHashMap<String, MutableSet<String>>()
        @Volatile
        private var mediaNotificationKey: String? = null
        @Volatile
        private var currentlyPlayingMediaPackage: String? = null
        // Regex patterns
        private val BRACKET_PATTERN: Pattern = Pattern.compile("\\(.*?\\)|\\[.*?]")
        private val MULTIPLE_SPACE_PATTERN: Pattern = Pattern.compile("\\s{2,}")
        private val WHITESPACE_PATTERN: Pattern = Pattern.compile("\\s+")
        private val PUNCTUATION_PATTERN: Pattern = Pattern.compile("[\\p{Punct}]")
        private var instanceRef: WeakReference<NotificationService>? = null
        @JvmStatic
        fun getPackagesForIconDisplay(): Set<String> {
            val packagesToDisplay = mutableSetOf<String>()
            val currentMediaPkg = currentlyPlayingMediaPackage // Cache volatile read
            activeNotificationsMap.entries.forEach { (packageName, keys) ->
                if (packageName.equals(EXCLUDED_PACKAGE, ignoreCase = true)) return@forEach
                if (packageName == currentMediaPkg) {
                    // Include the media package only if it has non-media notifications
                    val instance = instanceRef?.get()
                    val hasNonMediaNotification = instance?.activeNotifications?.any { sbn ->
                        packageName == sbn.packageName && keys.contains(sbn.key) && !instance.isMediaNotification(sbn)
                    } ?: false
                    if (hasNonMediaNotification) {
                        packagesToDisplay.add(packageName)
                    }
                } else if (keys.isNotEmpty()) {
                    // If not the media package, include it if it has any notifications
                    packagesToDisplay.add(packageName)
                }
            }
            Log.d(TAG, "Final packages for icon display: ${packagesToDisplay.size}")
            return packagesToDisplay
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
        // Clear state on connect
        activeNotificationsMap.clear()
        currentlyPlayingMediaPackage = null
        mediaNotificationKey = null
        clearSongTrackingState()
        // Initialize with currently active notifications
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
            // If a different app posts a media notification, check sessions to see if it's the new player
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
                sendSongNameBroadcast(null)
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
                DreamService.updateSongInfo(null)
                sendUpdateBroadcast()
            }
            return
        }
        val activeController = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.first() // Fallback to first if none are playing
        val controllerPackage = activeController.packageName
        val metadata = activeController.metadata
        val playbackState = activeController.playbackState
        // Update currently playing package status
        if (playbackState?.state == PlaybackState.STATE_PLAYING) {
            if (controllerPackage != currentlyPlayingMediaPackage) {
                currentlyPlayingMediaPackage = controllerPackage
                sendUpdateBroadcast() // Update icons
            }
        } else if (controllerPackage == currentlyPlayingMediaPackage) {
            // The package we thought was playing is no longer playing.
            currentlyPlayingMediaPackage = null
            DreamService.updateSongInfo(null)
            sendUpdateBroadcast()
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
                    } else if (trackedSongId == null) {
                        startTrackingSong(currentSongId, currentTime)
                    }
                    lastTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    lastArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    sendSongNameBroadcast(buildSongString(lastTitle, lastArtist))
                }
                sendUpdateBroadcast()
            }
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> {
                maybeCountAndClearTrackingSong(currentTime)
                if (packageName == currentlyPlayingMediaPackage) {
                    currentlyPlayingMediaPackage = null
                    mediaNotificationKey = null
                    lastTitle = null
                    lastArtist = null
                    sendSongNameBroadcast(null)
                }
                sendUpdateBroadcast()
            }
            else -> { // Null state or other states
                maybeCountAndClearTrackingSong(currentTime)
                if (packageName == currentlyPlayingMediaPackage) {
                    currentlyPlayingMediaPackage = null
                    mediaNotificationKey = null
                    lastTitle = null
                    lastArtist = null
                    sendSongNameBroadcast(null)
                }
                sendUpdateBroadcast()
            }
        }
    }
    private val mediaInfoRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MEDIA_INFO_REQUEST_ACTION) {
                checkMediaSessions()
                sendSongNameBroadcast(buildSongString(lastTitle, lastArtist))
            }
        }
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
        sendBroadcast(Intent(UPDATE_NOTIFICATIONS_ACTION))
        Log.d(TAG, "Broadcast sent: $UPDATE_NOTIFICATIONS_ACTION")
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
    // --- Song Counting and String Helpers ---
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
    private fun incrementAndBroadcastSongCount() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var lastResetTimestamp = prefs.getLong(PREF_LAST_RESET_TIMESTAMP, 0)
        var currentCount = prefs.getInt(PREF_SONG_COUNT, 0)
        val currentTime = System.currentTimeMillis()
        if (getStartOfDayTimestamp(currentTime) > getStartOfDayTimestamp(lastResetTimestamp)) {
            Log.d(TAG, "New day detected. Resetting song count.")
            currentCount = 0
            lastResetTimestamp = currentTime
        }
        currentCount++
        Log.d(TAG, "Incrementing song count to: $currentCount")
        prefs.edit {
            putInt(PREF_SONG_COUNT, currentCount)
            putLong(PREF_LAST_RESET_TIMESTAMP, lastResetTimestamp)
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