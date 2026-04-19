package com.bytesmith.daydream

import android.annotation.SuppressLint
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
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
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * NotificationListenerService that monitors media playback and notifications.
 * 
 * Refactored from ~607 lines to delegate to specialized classes:
 * - MediaSessionTracker: Handles media session monitoring and song tracking
 * - NotificationFilter: Handles package filtering for icon display
 * 
 * This class now serves as a coordinator/facade.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class NotificationService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var lastSentMetadataIntent: Intent? = null

    // Helper classes
    private lateinit var mediaSessionTracker: MediaSessionTracker

    // Media session check runnable
    private val mediaSessionCheckRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            mediaSessionTracker.checkAndResetSongCountIfNeeded()
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
        const val SONG_COUNT_UPDATED_ACTION = "com.bytesmith.daydream.SONG_COUNT_UPDATED"
    
        private const val MEDIA_SESSION_CHECK_INTERVAL = 5000L
    
        @Volatile
        var lastKnownMediaInfo: MediaInfo? = null
            private set
    
        @Volatile
        private var isDreamActive = false
    
        private var instanceRef: WeakReference<NotificationService>? = null
    
        @JvmStatic
        fun setDreamActive(isActive: Boolean) {
            isDreamActive = isActive
            Log.d(TAG, "Dream active status set to: $isDreamActive")
            if (isActive) {
                instanceRef?.get()?.apply {
                    lastSentMetadataIntent?.let { sendBroadcast(it) }
                    sendBroadcast(Intent(UPDATE_NOTIFICATIONS_ACTION))
                    broadcastCurrentSongCount()
                }
            }
        }
    
        @JvmStatic
        fun getPackagesForIconDisplay(): Set<String> {
            val instance = instanceRef?.get() ?: return emptySet()
            val tracker = instance.mediaSessionTracker
            return NotificationFilter.getPackagesForIconDisplay(
                activeNotificationsMap = instance.activeNotificationsMap,
                currentlyPlayingMediaPackage = tracker.currentlyPlayingMediaPackage,
                isMediaNotification = tracker::isMediaNotification,
                activeNotifications = instance.activeNotifications?.toList()
            )
        }
    
        @JvmStatic
        fun getSmallIconForPackage(context: Context, packageName: String): Drawable? {
            return try {
                val instance = instanceRef?.get() ?: return null
                val notifications = instance.activeNotifications ?: return null
                val sbn = notifications.firstOrNull { it.packageName == packageName } ?: return null
                val smallIcon = sbn.notification.smallIcon ?: return null
        
                // Try to load the drawable - may fail if the posting app was uninstalled
                // or if the icon references invalid resources
                smallIcon.loadDrawable(context)
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                // Package was uninstalled or not visible due to Android 11+ restrictions
                Log.w(TAG, "Package not found for icon: $packageName", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get small icon for package $packageName", e)
                null
            }
        }
    }
    
    /**
     * Data class representing media information.
     * Defined at class level to be accessible as NotificationService.MediaInfo
     */
    data class MediaInfo(
        val cleanedSongString: String?,
        val albumArt: Bitmap?,
        val cacheKey: String?
    )

    // Active notifications tracking
    private val activeNotificationsMap = ConcurrentHashMap<String, MutableSet<String>>()

    override fun onCreate() {
        super.onCreate()
        mediaSessionTracker = MediaSessionTracker(this)
        ContextCompat.registerReceiver(this, mediaInfoRequestReceiver, IntentFilter(MEDIA_INFO_REQUEST_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
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
        mediaSessionTracker.maybeCountAndClearTrackingSong(System.currentTimeMillis())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instanceRef = WeakReference(this)
        Log.d(TAG, "NotificationListener connected")
        activeNotificationsMap.clear()
        mediaSessionTracker.clearNotificationState()
        mediaSessionTracker.clearSongTrackingState()
        activeNotifications?.forEach { sbn -> onNotificationPosted(sbn, isInternalCall = true) }
        checkMediaSessions()
        sendUpdateBroadcast()
        if (mediaSessionTracker.currentlyPlayingMediaPackage != null && mediaSessionTracker.lastTitle != null) {
            sendSongNameBroadcast(buildSongString(mediaSessionTracker.lastTitle, mediaSessionTracker.lastArtist))
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
        if (NotificationFilter.isExcludedPackage(sbn.packageName)) return
        
        val packageName = sbn.packageName
        val key = sbn.key
        activeNotificationsMap.computeIfAbsent(packageName) { ConcurrentHashMap.newKeySet() }.add(key)
        mediaSessionTracker.addNotification(packageName, key)

        if (mediaSessionTracker.isMediaNotification(sbn)) {
            Log.d(TAG, "Posted notification IS a media notification: $key")
            if (packageName != mediaSessionTracker.currentlyPlayingMediaPackage) {
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
        
        if (mediaSessionTracker.isNotificationKeyForCurrentMedia(key)) {
            Log.d(TAG, "Media notification was removed: $key")
            mediaSessionTracker.mediaNotificationKey = null
            if (packageName == mediaSessionTracker.currentlyPlayingMediaPackage) {
                mediaSessionTracker.currentlyPlayingMediaPackage = null
                mediaSessionTracker.clearMetadata()
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
            if (mediaSessionTracker.currentlyPlayingMediaPackage != null) {
                Log.d(TAG, "Clearing currently playing package as no sessions are active.")
                mediaSessionTracker.currentlyPlayingMediaPackage = null
                mediaSessionTracker.clearMetadata()
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
            if (controllerPackage != mediaSessionTracker.currentlyPlayingMediaPackage) {
                mediaSessionTracker.currentlyPlayingMediaPackage = controllerPackage
                sendUpdateBroadcast()
            }
        }

        handlePlaybackStateChange(playbackState, controllerPackage, metadata)
    }

    @Suppress("DEPRECATION")
    private fun handlePlaybackStateChange(state: PlaybackState?, packageName: String, metadata: MediaMetadata?) {
        val currentTime = System.currentTimeMillis()
        val currentSongId = metadata?.let { buildSongId(it) }
        val prefs = getSharedPreferences("MediaPlaybackPrefs", Context.MODE_PRIVATE)
        val trackedSongId = prefs.getString("trackingSongId", null)

        when (state?.state) {
            PlaybackState.STATE_PLAYING -> {
                mediaSessionTracker.currentlyPlayingMediaPackage = packageName
                findAndSetMediaNotificationKey(packageName)

                if (currentSongId != null) {
                    if (currentSongId != trackedSongId) {
                        mediaSessionTracker.maybeCountAndClearTrackingSong(currentTime)
                        mediaSessionTracker.startTrackingSong(currentSongId, currentTime)
                    }

                    val lastTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    val lastArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

                    var albumArt: Bitmap? = null

                    if (metadata.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ART)) {
                        albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    }
                    if (albumArt == null && metadata.containsKey(MediaMetadata.METADATA_KEY_DISPLAY_ICON)) {
                        albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                    }

                    if (albumArt == null) {
                        val sbn = activeNotifications.firstOrNull { it.key == mediaSessionTracker.mediaNotificationKey }
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
                                is Bitmap -> albumArt = iconParcelable
                                is android.graphics.drawable.Icon -> albumArt = drawableToBitmap(iconParcelable.loadDrawable(this))
                            }
                        }
                    }

                    val cacheKey = "${lastTitle}_${lastArtist}_${album}_${duration}".replace(Regex("[^a-zA-Z0-9_]"), "")
                    sendMetadataBroadcast(lastTitle, lastArtist, albumArt, cacheKey)
                }
                sendUpdateBroadcast()
            }

            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> {
                mediaSessionTracker.maybeCountAndClearTrackingSong(currentTime)
                if (packageName == mediaSessionTracker.currentlyPlayingMediaPackage) {
                    mediaSessionTracker.currentlyPlayingMediaPackage = null
                }
                sendUpdateBroadcast()
            }
            
            else -> {
                mediaSessionTracker.maybeCountAndClearTrackingSong(currentTime)
                if (packageName == mediaSessionTracker.currentlyPlayingMediaPackage) {
                    mediaSessionTracker.currentlyPlayingMediaPackage = null
                    mediaSessionTracker.clearMetadata()
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

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        drawable ?: return null
        if (drawable is android.graphics.drawable.BitmapDrawable) {
            return drawable.bitmap
        }
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            return null
        }
        return try {
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth,
                drawable.intrinsicHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create bitmap from drawable", e)
            null
        }
    }

    private fun sendMetadataBroadcast(title: String?, artist: String?, art: Bitmap?, cacheKey: String?) {
        val songString = buildSongString(title, artist)
        lastKnownMediaInfo = MediaInfo(songString, art, cacheKey)

        val intent = Intent(MEDIA_METADATA_UPDATED_ACTION).apply {
            val bundle = Bundle().apply {
                putString("cleanedSongString", songString)
                putString("cacheKey", cacheKey)
            }
            putExtras(bundle)
        }
        lastSentMetadataIntent = intent
        if (!isDreamActive) return
        sendBroadcast(intent)
    }

    private fun clearMetadata() {
        lastKnownMediaInfo = null
        val intent = Intent(MEDIA_METADATA_UPDATED_ACTION)
        lastSentMetadataIntent = intent
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
        broadcastCurrentSongCount()
    }

    private fun broadcastCurrentSongCount() {
        if (!isDreamActive) return
        val currentCount = mediaSessionTracker.getCurrentSongCount()
        val intent = Intent(SONG_COUNT_UPDATED_ACTION).apply {
            putExtra("songCount", currentCount)
        }
        sendBroadcast(intent)
    }

    private fun sendSongNameBroadcast(songName: String?) {
        val intent = Intent(SONG_NAME_UPDATED_ACTION).apply {
            putExtra("songName", songName)
        }
        sendBroadcast(intent)
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

        var cleanedTitle = rawTitle.replace("%20", " ").trim()

        val lastDotIndex = cleanedTitle.lastIndexOf('.')
        if (lastDotIndex != -1) {
            cleanedTitle = cleanedTitle.substring(0, lastDotIndex).trim()
        }

        val suffixIndex = cleanedTitle.indexOf(" - ")
        if (suffixIndex != -1) {
            cleanedTitle = cleanedTitle.substring(0, suffixIndex).trim()
        }

        if (!rawArtist.isNullOrBlank() && cleanedTitle.isNotEmpty()) {
            val lowerCaseArtistWords = RegexPatterns.WHITESPACE.split(rawArtist.trim())
            .map { RegexPatterns.PUNCTUATION.matcher(it).replaceAll("").lowercase() }
            .filter { it.length > 1 }
            .toSet()
    
            if (lowerCaseArtistWords.isNotEmpty()) {
                cleanedTitle = RegexPatterns.WHITESPACE.split(cleanedTitle)
                .filter { word ->
                    val cleanWord = RegexPatterns.PUNCTUATION.matcher(word).replaceAll("").lowercase()
                    !lowerCaseArtistWords.contains(cleanWord)
                }
                .joinToString(" ")
            }
        }
    
        cleanedTitle = RegexPatterns.EMPTY_BRACKET.matcher(cleanedTitle).replaceAll("")
        cleanedTitle = RegexPatterns.MULTIPLE_SPACE.matcher(cleanedTitle).replaceAll(" ").trim()

        return cleanedTitle
    }

    private fun findAndSetMediaNotificationKey(targetPackageName: String) {
        mediaSessionTracker.mediaNotificationKey = activeNotifications.firstOrNull { sbn ->
            targetPackageName == sbn.packageName && mediaSessionTracker.isMediaNotification(sbn)
        }?.key
        if (mediaSessionTracker.mediaNotificationKey == null) {
            Log.w(TAG, "Could not find an active media notification key for package: $targetPackageName")
        }
    }
    }