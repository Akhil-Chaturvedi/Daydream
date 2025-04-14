package com.bytesmith.daydream;

import android.annotation.TargetApi;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.HashSet;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationService";
    private static final String UPDATE_NOTIFICATIONS_ACTION = "com.bytesmith.daydream.UPDATE_NOTIFICATIONS";
    private static final String PREFS_NAME = "MediaPlaybackPrefs";
    private static final String SONG_COUNT_KEY = "songCount";
    private static final String LAST_RESET_TIME_KEY = "lastResetTime";
    private static final long MIN_PLAYBACK_DURATION = 60000; // 1 minute
    private static final long MAX_PLAYBACK_DURATION = 600000; // 10 minutes
    private static final String MEDIA_INFO_REQUEST_ACTION = "com.bytesmith.daydream.REQUEST_MEDIA_INFO";
    private static final String SONG_NAME_UPDATED_ACTION = "com.bytesmith.daydream.SONG_NAME_UPDATED";
    private static final long MEDIA_SESSION_CHECK_INTERVAL = 5000; // Check every 5 seconds

    // Package to exclude from showing icons
    private static final String EXCLUDED_PACKAGE = "com.miui.securitycore";

    // Thread-safe set to store notification package names
    private static final Set<String> notificationPackages = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Song tracking
    private String currentSongName = "";
    private long songStartTime = 0;
    
    // Handler for media session checks
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;

    // Track the package currently playing media
    private static volatile String currentlyPlayingMediaPackage = null;

    // Handler for delayed song information updates
    private Handler delayedHandler = new Handler(Looper.getMainLooper());
    private static final int SONG_INFO_DELAY = 500; // Half-second delay
    private Runnable pendingSongUpdate = null;
    
    // Store current song information until we have a complete update
    private String lastTitle = null;
    private String lastArtist = null;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "NotificationListener connected");

        // Initialize with currently active notifications
        StatusBarNotification[] activeNotifications = getActiveNotifications();
        if (activeNotifications != null) {
            Log.d(TAG, "Found " + activeNotifications.length + " active notifications on connect");
            for (StatusBarNotification sbn : activeNotifications) {
                String packageName = sbn.getPackageName();
                if (!EXCLUDED_PACKAGE.equals(packageName)) {
                    notificationPackages.add(packageName);
                }
                if (isMediaNotification(sbn)) {
                    String songName = extractSongName(sbn);
                    if (songName != null && !songName.isEmpty()) {
                        Log.d(TAG, "Found active media notification on connect: " + songName + " from " + sbn.getPackageName());
                        sendSongNameBroadcast(songName); // Keep broadcast for other listeners
                    }
                }
            }
        } else {
            Log.d(TAG, "No active notifications found on connect");
        }
        sendUpdateBroadcast();
    }

    private boolean isCurrentlyPlaying(StatusBarNotification sbn) {
        if (sbn.getNotification() == null) return false;

        // Check if the notification is a media playback notification
        if (!Notification.CATEGORY_TRANSPORT.equals(sbn.getNotification().category)) return false;

        // Try checking playback state
        int state = sbn.getNotification().extras.getInt("android.mediaPlaybackState", -1);
        if (state == 3) return true; // STATE_PLAYING

        // Alternative: Check if media session actions exist
        return sbn.getNotification().actions != null && sbn.getNotification().actions.length > 0;
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;

        String packageName = sbn.getPackageName();
        if (notificationPackages.remove(packageName)) { // Remove only if it existed
            Log.d(TAG, "Notification removed: " + packageName);
            sendUpdateBroadcast();
        }
    }

    public static Set<String> getNotificationPackages() {
        // Filter out the excluded package before returning
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return notificationPackages.stream()
                    .filter(pkg -> !EXCLUDED_PACKAGE.equals(pkg))
                    .collect(Collectors.toSet());
        } else {
            // Manual filtering for older Android versions - Optimized: Use HashSet locally
            Set<String> filteredSet = new HashSet<>();
            for (String pkg : notificationPackages) {
                if (!EXCLUDED_PACKAGE.equals(pkg)) {
                    filteredSet.add(pkg);
                }
            }
            return Collections.unmodifiableSet(filteredSet); // Return unmodifiable view
        }
    }

    /**
     * Sends a broadcast only when there's an update to the *filtered* list.
     */
    private void sendUpdateBroadcast() {
        // Consider sending the filtered list if needed, or just signal an update
        Intent intent = new Intent(UPDATE_NOTIFICATIONS_ACTION);
        // Optionally add the filtered package list to the intent if DreamService needs it directly
        // intent.putExtra("packages", new ArrayList<>(getNotificationPackages()));
        sendBroadcast(intent);
        Log.d(TAG, "Broadcast sent: " + UPDATE_NOTIFICATIONS_ACTION);
    }

    private boolean isMediaNotification(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            Log.d(TAG, "Notification is null, not a media notification");
            return false;
        }
        
        // Debug the notification
        Log.d(TAG, "Checking if notification is media: " + sbn.getPackageName() + ", Category: " + sbn.getNotification().category);
        
        // Check if it's explicitly a media transport notification
        if (Notification.CATEGORY_TRANSPORT.equals(sbn.getNotification().category)) {
            Log.d(TAG, "Is media notification based on CATEGORY_TRANSPORT");
            return true;
        }
        
        // Check media style
        boolean hasMediaStyle = false;
        if (sbn.getNotification().extras.containsKey("android.mediaSession")) {
            hasMediaStyle = true;
            Log.d(TAG, "Is media notification based on mediaSession extra");
        }
        
        // Check for media metadata
        if (sbn.getNotification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            hasMediaStyle = true;
            Log.d(TAG, "Is media notification based on EXTRA_MEDIA_SESSION");
        }
        
        // REMOVED Check for known media player packages - Rely on standard indicators instead
        /* 
        String[] knownMediaPackages = { ... };
        for (String pkg : knownMediaPackages) {
            if (pkg.equals(sbn.getPackageName())) {
                Log.d(TAG, "Is media notification based on known package: " + pkg);
                return true;
            }
        }
        */
        
        // Check for media session actions which might indicate a media notification
        if (sbn.getNotification().actions != null && sbn.getNotification().actions.length > 0) {
            Log.d(TAG, "Notification has " + sbn.getNotification().actions.length + " actions");
            if (hasActionWithTitle(sbn.getNotification().actions, "Play") ||
                hasActionWithTitle(sbn.getNotification().actions, "Pause") ||
                hasActionWithTitle(sbn.getNotification().actions, "Stop") ||
                hasActionWithTitle(sbn.getNotification().actions, "Previous") ||
                hasActionWithTitle(sbn.getNotification().actions, "Next")) {
                Log.d(TAG, "Is media notification based on action titles");
                return true;
            }
        }
        
        return hasMediaStyle;
    }
    
    private boolean hasActionWithTitle(@NonNull Notification.Action[] actions, @NonNull String title) {
        // Optimized: Convert title to lower case once
        String lowerCaseTitle = title.toLowerCase();
        for (Notification.Action action : actions) {
            if (action != null && action.title != null &&
                action.title.toString().toLowerCase().contains(lowerCaseTitle)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldResetCount(long lastResetTime, long currentTime) {
        Calendar lastResetCalendar = Calendar.getInstance();
        lastResetCalendar.setTimeInMillis(lastResetTime);
        Calendar currentCalendar = Calendar.getInstance();
        currentCalendar.setTimeInMillis(currentTime);

        return lastResetCalendar.get(Calendar.DAY_OF_YEAR) != currentCalendar.get(Calendar.DAY_OF_YEAR) ||
                lastResetCalendar.get(Calendar.YEAR) != currentCalendar.get(Calendar.YEAR);
    }

    private void resetSongCount(SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(SONG_COUNT_KEY, 0);
        editor.putLong(LAST_RESET_TIME_KEY, System.currentTimeMillis());
        editor.apply();
    }

    private void sendSongNameBroadcast(String songName) {
        Intent intent = new Intent(SONG_NAME_UPDATED_ACTION);
        intent.putExtra("songName", songName);
        sendBroadcast(intent);
        Log.d(TAG, "Sent song name broadcast: " + songName);
    }

    private String extractSongName(@Nullable StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null || sbn.getNotification().extras == null) {
            Log.d(TAG, "Notification or extras is null for SBN: " + (sbn != null ? sbn.getPackageName() : "null"));
            return null; // Return null if no data
        }

        Bundle extras = sbn.getNotification().extras;
        String packageName = sbn.getPackageName();
        Log.d(TAG, "Extracting song name from notification: " + packageName);

        // Prioritize MediaMetadata if available (often more reliable)
        MediaMetadata metadata = extras.getParcelable(Notification.EXTRA_MEDIA_METADATA);
        if (metadata != null) {
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
             Log.d(TAG, "Extracted from MediaMetadata: title='" + title + "', artist='" + artist + "'");
            return buildSongString(title, artist);
        }

        // Fallback to standard notification extras
        CharSequence titleChars = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence artistChars = extras.getCharSequence(Notification.EXTRA_TEXT); // Often artist or album

        String title = (titleChars != null) ? titleChars.toString() : null;
        String artist = (artistChars != null) ? artistChars.toString() : null;
        Log.d(TAG, "Extracted from Notification Extras: title='" + title + "', artist='" + artist + "'");

        return buildSongString(title, artist);
    }

    @Override
    public void onNotificationPosted(@Nullable StatusBarNotification sbn) {
        if (sbn == null) {
            Log.w(TAG, "onNotificationPosted received null sbn");
            return;
        }
        String packageName = sbn.getPackageName();
        Log.d(TAG, "Notification posted: " + packageName + " ID: " + sbn.getId());

        // Add package to the set (filtering happens when getNotificationPackages is called)
        if (!EXCLUDED_PACKAGE.equals(packageName)) {
             if (notificationPackages.add(packageName)) { // Add returns true if not already present
                 Log.d(TAG, "Added package to set: " + packageName);
                 sendUpdateBroadcast(); // Send update if the set changed
             }
        } else {
             Log.d(TAG, "Ignoring excluded package: " + packageName);
        }

        // Check if it's a media notification
        if (isMediaNotification(sbn)) {
            Log.d(TAG, "Notification identified as media: " + packageName);
            currentlyPlayingMediaPackage = packageName; // Update the currently playing package

            String songName = extractSongName(sbn);
            MediaSession.Token token = sbn.getNotification().extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);

            Log.d(TAG, "Extracted Song: " + songName + ", Token: " + (token != null));

            if (!TextUtils.isEmpty(songName)) {
                Log.d(TAG, "Processing media playback for: " + songName);
                processMediaPlayback(songName, System.currentTimeMillis());
                // Update DreamService immediately with extracted info
                DreamService.updateSongInfo(songName, token);
                // Also send broadcast for any other potential listeners
                sendSongNameBroadcast(songName);
            } else {
                Log.d(TAG, "Media notification found, but song name is empty.");
                // Optionally clear dream service info if song name becomes empty but token exists?
                 // DreamService.updateSongInfo(null, token);
            }
            // Send update broadcast regardless to potentially update icon visibility in DreamService
             sendUpdateBroadcast();
        } else {
             Log.d(TAG, "Notification is not media: " + packageName);
             // If a non-media notification comes from the currently playing app, don't clear it
             // Only clear if the notification causing removal is NOT media
             if (packageName.equals(currentlyPlayingMediaPackage)) {
                  // Maybe the media session ended, check active sessions?
                  // Or maybe it's just a different notification from the same app.
                  // For now, do nothing here, rely on checkMediaSessions or removal event.
             }
        }
    }
    
    // Removed handleSongCountUpdate as logic is now in processMediaPlayback
    // private void handleSongCountUpdate(...) { ... }
    
    // Central method for processing playback state and updating count
    private synchronized void processMediaPlayback(String songName, long currentTime) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Check if we need to reset the song count for a new day
        long lastResetTime = prefs.getLong(LAST_RESET_TIME_KEY, 0);
        if (shouldResetCount(lastResetTime, currentTime)) {
            Log.d(TAG, "New day detected, resetting song count.");
            resetSongCount(prefs);
            // Also reset tracking state for the new day
            currentSongName = songName; // Start tracking the current song
            songStartTime = currentTime;
            Log.d(TAG, "Reset tracking for new day. Current song: " + currentSongName + ", Start time: " + songStartTime);
             // Save initial start time for the new song/day
        SharedPreferences.Editor editor = prefs.edit();
            editor.putLong("songStartTime", songStartTime); 
            editor.apply();
            return; // Don't process duration on reset
        }
        
        // Now call the existing duration/counting logic
        handleSongUpdate(songName, currentTime, prefs);
    }

    // handleSongUpdate remains mostly the same, handles duration check and count increment
    private void handleSongUpdate(String songName, long currentTime, SharedPreferences prefs) {
        Log.d(TAG, "Handling song update/duration check for: " + songName + ", Current Time: " + currentTime);
        
        long savedStartTime = prefs.getLong("songStartTime", 0);
        Log.d(TAG, "  Current Tracked Song: " + currentSongName + ", Saved Start Time: " + savedStartTime);

        // If song changed OR there is no valid start time stored in prefs, reset start time
        if (!songName.equals(currentSongName) || savedStartTime == 0) {
            currentSongName = songName; // Update tracked song name
            songStartTime = currentTime; // Update local start time
            SharedPreferences.Editor editor = prefs.edit();
            editor.putLong("songStartTime", songStartTime); // Save new start time
            editor.putString("lastSong", currentSongName); // Also save the current song name
            editor.apply();
            Log.d(TAG, "New song detected ('" + songName + "') or first detection. Setting start time to: " + songStartTime + " (saved in prefs)");
            return; // Wait for the next update to calculate duration
        }

        // If song is the same and we have a valid start time, calculate duration
        long playbackDuration = currentTime - savedStartTime;
        Log.d(TAG, "Calculated playback duration for '" + songName + "': " + playbackDuration + " ms (using saved start time: "+ savedStartTime +")");

        // Check if duration is within range to count the song
        if (playbackDuration >= MIN_PLAYBACK_DURATION && playbackDuration <= MAX_PLAYBACK_DURATION) {
            Log.d(TAG, "Playback duration within range. Attempting to increment count.");
            
            int songCount = prefs.getInt(SONG_COUNT_KEY, 0);
            Log.d(TAG, "  Current count from prefs: " + songCount);
            songCount++;
            
            // Save the updated count BUT RESET START TIME IN PREFS to prevent recounting
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(SONG_COUNT_KEY, songCount);
            editor.putLong("songStartTime", 0); // Reset start time in prefs immediately after counting
            // editor.putString("lastSong", ""); // Clear last song maybe?
            editor.apply();
            Log.d(TAG, "  Saved new count: " + songCount + " and **RESET** songStartTime in prefs to prevent recount.");
            
            // Update UI
            DreamService.updateSongCount(songCount);
            Log.d(TAG, "Song count incremented to: " + songCount + " in UI");
            
            // Reset local state
            songStartTime = 0; // Reset local start time as well
            // currentSongName = ""; // Keep current song name until a *new* one is detected
            Log.d(TAG, "Reset local songStartTime after counting.");
            
        } else {
            Log.d(TAG, "Playback duration (" + playbackDuration + "ms) not within required range [" + MIN_PLAYBACK_DURATION + "-" + MAX_PLAYBACK_DURATION + "]ms. Not incrementing count.");
            // Do NOT reset start time here, allow duration to accumulate further.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        
        // Register for media info requests
        IntentFilter filter = new IntentFilter(MEDIA_INFO_REQUEST_ACTION);
        registerReceiver(mediaInfoRequestReceiver, filter);
        
        // Start periodic media session checks
        startMediaSessionChecks();
    }
    
    @Override
    public void onDestroy() {
        stopMediaSessionChecks();
        
        try {
            unregisterReceiver(mediaInfoRequestReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver", e);
        }
        super.onDestroy();
    }
    
    private final BroadcastReceiver mediaInfoRequestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MEDIA_INFO_REQUEST_ACTION.equals(intent.getAction())) {
                Log.d(TAG, "Received request for media info");
                checkExistingMediaNotifications();
            }
        }
    };
    
    private void checkExistingMediaNotifications() {
        StatusBarNotification[] activeNotifications = getActiveNotifications();
        if (activeNotifications == null) return;
        
        for (StatusBarNotification sbn : activeNotifications) {
            if (isMediaNotification(sbn)) {
                String songName = extractSongName(sbn); // This will trigger the delayed update
                if (songName != null && !songName.isEmpty()) {
                    Log.d(TAG, "Found active media notification with song: " + songName);
                     // REMOVED: Immediate update bypassed delay/final filtering
                    // DreamService.updateSongName(songName);
                    sendSongNameBroadcast(songName); // Keep broadcast for other listeners
                    return; // Still use the first one found for the broadcast, but rely on delayed update for UI
                }
            }
        }
    }
    
    private void startMediaSessionChecks() {
        isRunning = true;
        handler.post(mediaSessionCheckRunnable);
    }
    
    private void stopMediaSessionChecks() {
        isRunning = false;
        handler.removeCallbacks(mediaSessionCheckRunnable);
        Log.d(TAG, "Stopped periodic media session checks.");
    }
    
    private final Runnable mediaSessionCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            
            checkMediaSessions();
            
            // Schedule next check
            handler.postDelayed(this, MEDIA_SESSION_CHECK_INTERVAL);
        }
    };
    
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void checkMediaSessions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        try {
            MediaSessionManager mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (mediaSessionManager == null) {
                Log.e(TAG, "MediaSessionManager is null, cannot check sessions.");
                scheduleNextMediaCheck(); // Reschedule even if manager is null
                return;
            }

            ComponentName componentName = new ComponentName(this, NotificationService.class);
            List<MediaController> controllers = mediaSessionManager.getActiveSessions(componentName);

            if (controllers == null || controllers.isEmpty()) {
                Log.d(TAG, "No active media sessions found.");
                 // If no sessions are active, potentially clear the currently playing info
                 if (currentlyPlayingMediaPackage != null) {
                     Log.d(TAG, "Clearing currently playing package as no sessions are active.");
                     currentlyPlayingMediaPackage = null;
                     DreamService.updateSongInfo(null, null);
                     sendUpdateBroadcast(); // Update icons in DreamService
                 }
                scheduleNextMediaCheck();
                return;
            }

            Log.d(TAG, "Found " + controllers.size() + " active media session(s).");
            boolean foundPlaying = false;

            // Find the 'best' controller (e.g., the one actually playing)
            MediaController activeController = null;
            for (MediaController controller : controllers) {
                 if (controller == null) continue;
                 PlaybackState playbackState = controller.getPlaybackState();
                 if (playbackState != null && playbackState.getState() == PlaybackState.STATE_PLAYING) {
                     activeController = controller;
                     Log.d(TAG, "Found actively playing session: " + controller.getPackageName());
                     foundPlaying = true;
                     break; // Found playing session, use this one
                 }
             }
             
             // If no session is actively playing, pick the first one as a fallback (might be paused)
             if (activeController == null) {
                 activeController = controllers.get(0);
                 Log.d(TAG, "No actively playing session found, using first controller: " + activeController.getPackageName());
             }

            // Process the chosen controller
            final MediaController finalActiveController = activeController; // Final for use in callback
            String controllerPackage = finalActiveController.getPackageName();
            MediaMetadata metadata = finalActiveController.getMetadata();
            PlaybackState playbackState = finalActiveController.getPlaybackState();

            // Update currently playing package
             if (playbackState != null && playbackState.getState() == PlaybackState.STATE_PLAYING) {
                 if (!controllerPackage.equals(currentlyPlayingMediaPackage)) {
                     Log.d(TAG, "Updating currently playing package to: " + controllerPackage);
                     currentlyPlayingMediaPackage = controllerPackage;
                     sendUpdateBroadcast(); // Update icons
                 }
             } else if (controllerPackage.equals(currentlyPlayingMediaPackage) && !foundPlaying) {
                 // The package we thought was playing is no longer playing, and no other session is playing.
                 // Clear the currently playing status.
                 Log.d(TAG, "Previously playing package ("+controllerPackage+") is no longer playing. Clearing status.");
                 currentlyPlayingMediaPackage = null;
                 DreamService.updateSongInfo(null, null);
                 sendUpdateBroadcast();
             }


            final MediaController.Callback mediaCallback = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(@Nullable PlaybackState state) {
                    if (state == null) return;
                    Log.d(TAG, "MediaController Callback: PlaybackState changed for " +
                          finalActiveController.getPackageName() + ": " + state.getState());
                    // OPTIMIZED: Directly update UI/state, already on main thread
                    handlePlaybackStateChange(state, finalActiveController.getSessionToken(), finalActiveController.getPackageName(), finalActiveController.getMetadata());
                }

                @Override
                public void onMetadataChanged(@Nullable MediaMetadata metadata) {
                    if (metadata == null) return;
                    Log.d(TAG, "MediaController Callback: Metadata changed for " +
                          finalActiveController.getPackageName());
                    // OPTIMIZED: Directly update UI/state, already on main thread
                    handleMetadataChange(metadata, finalActiveController.getSessionToken());
                }
            };

            // Register callback to get updates
            // Need to ensure we unregister this callback later if controller becomes invalid
            // For simplicity of polling, maybe don't register callback here, just extract info?
            // Let's stick to extracting info directly for now to avoid callback lifecycle management.
            // finalActiveController.registerCallback(mediaCallback, handler);

             // --- Direct Extraction Logic --- 
             handlePlaybackStateChange(playbackState, finalActiveController.getSessionToken(), controllerPackage, metadata);
             // --------------------------------

            scheduleNextMediaCheck();
        } catch (SecurityException se) {
            Log.e(TAG, "Security exception checking media sessions", se);
        } catch (Exception e) {
            Log.e(TAG, "Error checking media sessions", e);
        }
    }

    // Helper method to handle state changes (called directly now)
    private void handlePlaybackStateChange(@Nullable PlaybackState state, @Nullable MediaSession.Token token, @NonNull String packageName, @Nullable MediaMetadata metadata) {
         if (state == null) return;
         int playbackStateInt = state.getState();

         if (playbackStateInt == PlaybackState.STATE_PLAYING) {
             if (!packageName.equals(currentlyPlayingMediaPackage)) {
                 Log.d(TAG, "Playback started for different package: " + packageName);
                 currentlyPlayingMediaPackage = packageName;
                 sendUpdateBroadcast();
             }
             // Use existing metadata if available, otherwise rely on metadata change callback
             handleMetadataChange(metadata != null ? metadata : getCurrentMetadataFromController(token), token);
         } else {
             // If the currently playing package is no longer playing
             if (packageName.equals(currentlyPlayingMediaPackage)) {
                 Log.d(TAG, "Playback stopped/paused for currently tracked package: " + packageName);
                 // Don't clear song info immediately, let metadata check handle it or timeout?
                 // Maybe clear if state is STOPPED or ERROR?
                 if (playbackStateInt == PlaybackState.STATE_STOPPED || playbackStateInt == PlaybackState.STATE_ERROR) {
                    currentlyPlayingMediaPackage = null;
                    DreamService.updateSongInfo(null, null);
                    sendUpdateBroadcast();
                 }
             }
         }
    }
    
    // Helper method to handle metadata changes (called directly now)
    private void handleMetadataChange(@Nullable MediaMetadata metadata, @Nullable MediaSession.Token token) {
        if (metadata == null) {
             Log.d(TAG, "Metadata is null in handleMetadataChange");
            // If metadata becomes null for the current session, clear the display?
            // Let's clear only if the state is also not playing.
             // DreamService.updateSongInfo(null, token);
             return;
        }
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String songName = buildSongString(title, artist);

        if (!TextUtils.isEmpty(songName)) {
            Log.d(TAG, "Updating DreamService with metadata: " + songName);
            // Use the delayed handler to avoid rapid updates if title/artist change quickly
            if (pendingSongUpdate != null) {
                delayedHandler.removeCallbacks(pendingSongUpdate);
            }
            pendingSongUpdate = () -> {
                DreamService.updateSongInfo(songName, token);
                sendSongNameBroadcast(songName); // Also update broadcast
                processMediaPlayback(songName, System.currentTimeMillis()); // Update count
            };
            delayedHandler.postDelayed(pendingSongUpdate, SONG_INFO_DELAY);
        } else {
             Log.d(TAG, "Metadata updated, but extracted song name is empty.");
             // Clear info if song becomes empty
             // DreamService.updateSongInfo(null, token);
        }
    }
    
    // Helper to get current metadata if needed
    @Nullable
    private MediaMetadata getCurrentMetadataFromController(@Nullable MediaSession.Token token) {
         if (token == null) return null;
         try {
             MediaController controller = new MediaController(this, token);
             return controller.getMetadata();
         } catch (Exception e) {
             Log.e(TAG, "Error creating temporary MediaController to get metadata", e);
             return null;
         }
    }

    public static synchronized String getCurrentlyPlayingMediaPackage() {
        return currentlyPlayingMediaPackage;
    }

    // Helper method to convert a string to Title Case
    private String toTitleCase(String input) {
        if (TextUtils.isEmpty(input)) {
            return input;
        }
        StringBuilder titleCase = new StringBuilder(input.length());
        boolean nextTitleCase = true;

        for (char c : input.toCharArray()) {
            if (Character.isSpaceChar(c)) {
                nextTitleCase = true;
            } else if (nextTitleCase) {
                c = Character.toTitleCase(c);
                nextTitleCase = false;
            } else {
                c = Character.toLowerCase(c); // Ensure rest is lower case
            }
            titleCase.append(c);
        }
        return titleCase.toString();
    }

    private String buildSongString(@Nullable String title, @Nullable String artist) {
        String cleanTitle = applyFilters(toTitleCase(title));
        String cleanArtist = applyFilters(toTitleCase(artist));

        // Use TextUtils.isEmpty for null/empty checks
        boolean hasTitle = !TextUtils.isEmpty(cleanTitle);
        boolean hasArtist = !TextUtils.isEmpty(cleanArtist);

        if (hasTitle && hasArtist) {
            // Check if artist info is already contained within the title (e.g., "Song Title - Artist")
            if (cleanTitle.toLowerCase().contains(cleanArtist.toLowerCase())) {
                return cleanTitle; // Return only title if artist is redundant
            }
            // Use StringBuilder for concatenation
            return new StringBuilder().append(cleanTitle).append('\n').append(cleanArtist).toString();
        } else if (hasTitle) {
            return cleanTitle;
        } else if (hasArtist) {
            return cleanArtist; // Should this happen? Maybe return null?
        } else {
            return null; // Return null if both are empty
        }
    }

    // Helper method to apply filters to song/artist strings
    private String applyFilters(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        // 1. Remove content within parentheses (including parentheses)
        String filtered = input.replaceAll("\\(.*?\\)", "").trim();
        
        // 2. Remove content after the first dot (including the dot)
        int dotIndex = filtered.indexOf('.');
        if (dotIndex != -1) {
            filtered = filtered.substring(0, dotIndex).trim();
        }
        
        // Trim again just in case
        return filtered.trim(); 
    }

    // Helper method to find the currently active playing MediaSession token
    private MediaSession.Token findActiveMediaSessionToken() {
        MediaSessionManager mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (mediaSessionManager == null) return null;
        
        try {
            ComponentName componentName = new ComponentName(this, this.getClass());
            List<MediaController> controllers = mediaSessionManager.getActiveSessions(componentName);
            
            for (MediaController controller : controllers) {
                if (controller == null) continue;
                PlaybackState playbackState = controller.getPlaybackState();
                if (playbackState != null && playbackState.getState() == PlaybackState.STATE_PLAYING) {
                    // Found the active playing session
                     Log.d(TAG, "Found active token for package: " + controller.getPackageName());
                    return controller.getSessionToken();
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException finding active media session token", e);
        } catch (Exception e) {
             Log.e(TAG, "Exception finding active media session token", e);
        }
        Log.d(TAG, "No active playing media session token found.");
        return null;
    }
}