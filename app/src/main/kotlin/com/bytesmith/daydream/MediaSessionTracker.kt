package com.bytesmith.daydream

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import androidx.core.content.edit
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Handles media session monitoring and song tracking.
 * Extracted from NotificationService for better separation of concerns.
 */
class MediaSessionTracker(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // State
    @Volatile
    var currentlyPlayingMediaPackage: String? = null
    
    @Volatile
    var mediaNotificationKey: String? = null
    
    @Volatile
    var lastTitle: String? = null
    
    @Volatile
    var lastArtist: String? = null
    
    @Volatile
    var lastKnownMediaInfo: NotificationService.MediaInfo? = null

    // Active notifications tracking
    private val activeNotificationsMap = ConcurrentHashMap<String, MutableSet<String>>()

    companion object {
        private const val TAG = "MediaSessionTracker"
        private const val PREFS_NAME = "MediaPlaybackPrefs"
        private const val PREF_SONG_COUNT = "songCount"
        private const val PREF_LAST_RESET_TIMESTAMP = "lastResetTimestamp"
        private const val PREF_TRACKING_SONG_ID = "trackingSongId"
        private const val PREF_TRACKING_SONG_START_TIME = "trackingSongStartTime"
        private const val MIN_PLAYBACK_DURATION_MS = 30000L
        private const val MAX_PLAYBACK_DURATION_MS = 600000L
    }

    /**
     * Checks active media sessions and updates state.
     */
    fun checkMediaSessions(
        activeNotifications: List<android.service.notification.StatusBarNotification>,
        onPackageChanged: () -> Unit,
        onMetadataChanged: (String?, String?, Bitmap?, String?) -> Unit,
        onClearMetadata: () -> Unit
    ) {
        val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        mediaSessionManager ?: return
        
        val componentName = ComponentName(context, NotificationService::class.java)
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
                onClearMetadata()
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
                onPackageChanged()
            }
        }

        handlePlaybackStateChange(
            playbackState, 
            controllerPackage, 
            metadata,
            activeNotifications,
            onMetadataChanged
        )
    }

    private fun handlePlaybackStateChange(
        state: PlaybackState?,
        packageName: String,
        metadata: MediaMetadata?,
        activeNotifications: List<android.service.notification.StatusBarNotification>,
        onMetadataChanged: (String?, String?, Bitmap?, String?) -> Unit
    ) {
        val currentTime = System.currentTimeMillis()
        val currentSongId = metadata?.let { buildSongId(it) }
        val trackedSongId = prefs.getString(PREF_TRACKING_SONG_ID, null)

        when (state?.state) {
            PlaybackState.STATE_PLAYING -> {
                currentlyPlayingMediaPackage = packageName
                findAndSetMediaNotificationKey(packageName, activeNotifications)

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
                    }
                    if (albumArt == null && metadata.containsKey(MediaMetadata.METADATA_KEY_DISPLAY_ICON)) {
                        albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                    }

                    if (albumArt == null) {
                        val sbn = activeNotifications.firstOrNull { it.key == mediaNotificationKey }
                        if (sbn != null) {
                            val iconParcelable = getParcelableCompat(
                                sbn.notification?.extras ?: Bundle.EMPTY,
                                Notification.EXTRA_LARGE_ICON_BIG,
                                Parcelable::class.java
                            ) ?: @Suppress("DEPRECATION") getParcelableCompat(
                                sbn.notification?.extras ?: Bundle.EMPTY,
                                Notification.EXTRA_LARGE_ICON,
                                Parcelable::class.java
                            )

                            when (iconParcelable) {
                                is Bitmap -> albumArt = iconParcelable
                                is Icon -> albumArt = drawableToBitmap(iconParcelable.loadDrawable(context))
                            }
                        }
                    }

                    val cacheKey = "${lastTitle}_${lastArtist}_${album}_${duration}".replace(Regex("[^a-zA-Z0-9_]"), "")
                    lastKnownMediaInfo = NotificationService.MediaInfo(
                        buildSongString(lastTitle, lastArtist),
                        albumArt,
                        cacheKey
                    )
                    onMetadataChanged(lastTitle, lastArtist, albumArt, cacheKey)
                }
            }

            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> {
                maybeCountAndClearTrackingSong(currentTime)
                if (packageName == currentlyPlayingMediaPackage) {
                    currentlyPlayingMediaPackage = null
                }
            }
            
            else -> {
                maybeCountAndClearTrackingSong(currentTime)
                if (packageName == currentlyPlayingMediaPackage) {
                    currentlyPlayingMediaPackage = null
                    clearMetadata()
                }
            }
        }
    }

    fun clearMetadata() {
        lastKnownMediaInfo = null
        lastTitle = null
        lastArtist = null
    }

    fun addNotification(packageName: String, key: String) {
        activeNotificationsMap.computeIfAbsent(packageName) { ConcurrentHashMap.newKeySet() }.add(key)
    }

    fun removeNotification(packageName: String, key: String): Boolean {
        val keys = activeNotificationsMap[packageName]
        if (keys != null) {
            if (keys.remove(key)) {
                if (keys.isEmpty()) {
                    activeNotificationsMap.remove(packageName)
                }
                return true
            }
        }
        return false
    }

    fun isNotificationKeyForCurrentMedia(key: String): Boolean = key == mediaNotificationKey

    fun clearNotificationState() {
        activeNotificationsMap.clear()
        mediaNotificationKey = null
        currentlyPlayingMediaPackage = null
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
        if (drawable is BitmapDrawable) {
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
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create bitmap from drawable", e)
            null
        }
    }

    private fun findAndSetMediaNotificationKey(targetPackageName: String, activeNotifications: List<android.service.notification.StatusBarNotification>) {
        mediaNotificationKey = activeNotifications.firstOrNull { sbn ->
            targetPackageName == sbn.packageName && isMediaNotification(sbn)
        }?.key
        if (mediaNotificationKey == null) {
            Log.w(TAG, "Could not find an active media notification key for package: $targetPackageName")
        }
    }

    @SuppressLint("NewApi")
    fun isMediaNotification(sbn: android.service.notification.StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        if (notification.extras.containsKey("android.mediaSession")) return true
        if (notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true
        return notification.actions?.any { action ->
            val title = action.title.toString()
            title.equals("Play", ignoreCase = true) || title.equals("Pause", ignoreCase = true) ||
            title.equals("Stop", ignoreCase = true) || title.equals("Previous", ignoreCase = true) ||
            title.equals("Next", ignoreCase = true)
        } ?: false
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

    @Synchronized
    fun checkAndResetSongCountIfNeeded(): Boolean {
        val lastResetTimestamp = prefs.getLong(PREF_LAST_RESET_TIMESTAMP, 0)
        val currentTime = System.currentTimeMillis()

        if (getStartOfDayTimestamp(currentTime) > getStartOfDayTimestamp(lastResetTimestamp)) {
            prefs.edit {
                putInt(PREF_SONG_COUNT, 0)
                putLong(PREF_LAST_RESET_TIMESTAMP, currentTime)
            }
            return true
        }
        return false
    }

    @Synchronized
    fun incrementAndBroadcastSongCount(): Int {
        checkAndResetSongCountIfNeeded()
        var currentCount = prefs.getInt(PREF_SONG_COUNT, 0)
        currentCount++
        prefs.edit { putInt(PREF_SONG_COUNT, currentCount) }
        return currentCount
    }

    @Synchronized
    fun maybeCountAndClearTrackingSong(endTime: Long): Boolean {
        val startTime = prefs.getLong(PREF_TRACKING_SONG_START_TIME, -1)
        val songId = prefs.getString(PREF_TRACKING_SONG_ID, null)
        if (startTime != -1L && songId != null) {
            val duration = endTime - startTime
            if (duration in MIN_PLAYBACK_DURATION_MS..MAX_PLAYBACK_DURATION_MS) {
                incrementAndBroadcastSongCount()
                return true
            }
        }
        clearSongTrackingState()
        return false
    }

    @Synchronized
    fun startTrackingSong(songId: String, startTime: Long) {
        prefs.edit {
            putString(PREF_TRACKING_SONG_ID, songId)
            putLong(PREF_TRACKING_SONG_START_TIME, startTime)
        }
    }

    @Synchronized
    fun clearSongTrackingState() {
        prefs.edit {
            remove(PREF_TRACKING_SONG_ID)
            remove(PREF_TRACKING_SONG_START_TIME)
        }
    }

    fun getCurrentSongCount(): Int = prefs.getInt(PREF_SONG_COUNT, 0)

    private fun getStartOfDayTimestamp(timestamp: Long): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}