package com.bytesmith.daydream;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.lang.ref.WeakReference;
import android.graphics.Color;
import android.os.Build;
import android.view.WindowCallbackWrapper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class DreamService extends android.service.dreams.DreamService {

    private static final String TAG = "DreamService";
    private static final long UPDATE_INTERVAL = 1000; // 1 second for time and date updates
    private static final long BATTERY_UPDATE_INTERVAL = 60000; // 1 minute for battery updates
    private static final long SHIFT_DURATION = 10000; // 10 seconds for text shifting
    private static final int SHIFT_AMOUNT = 100; // Pixel shift amount
    private static final int MAX_SHIFTS = 5;
    private static final int ICON_TEXT_SPACING_DP = 4; // Constant for spacing


    private TextView timeInWordsTextView;
    private TextView dayDateTextView;
    private TextView batteryInfoTextView;
    private TextView songCountTextView;
    private TextView songNameTextView;
    private ImageView batteryIconImageView;
    private LinearLayout notificationIconContainer;
    private File iconCacheDir;
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static WeakReference<DreamService> instanceRef;
    private int shiftCount = 0;
    private static int songCount = 0;
    private boolean isUpdating = false;
    private GestureDetector gestureDetector;
    private static MediaController currentMediaController = null;
    private static final Object controllerLock = new Object(); // Lock for static controller access

    // Reusable formatters
    private static final SimpleDateFormat TIME_WORDS_FORMAT = new SimpleDateFormat("hh_mm_a", Locale.getDefault());
    private static final SimpleDateFormat DAY_DATE_FORMAT = new SimpleDateFormat("EEEE, MMM d", Locale.getDefault());

    @Override
    public void onCreate() {
        super.onCreate();
        instanceRef = new WeakReference<>(this);
    }

    private final Runnable updateTimeRunnable = new Runnable() {
        @Override
        public void run() {
            updateTimeInWords();
            handler.postDelayed(this, UPDATE_INTERVAL);
        }
    };

    private final Runnable updateDateRunnable = new Runnable() {
        @Override
        public void run() {
            updateDayDateTextView();
            handler.postDelayed(this, UPDATE_INTERVAL);
        }
    };

    private final Runnable updateSongCountRunnable = new Runnable() {
        @Override
        public void run() {
            updateSongCount(songCount);
            handler.postDelayed(this, UPDATE_INTERVAL);
        }
    };

    private final Runnable updateBatteryRunnable = new Runnable() {
        @Override
        public void run() {
            updateBatteryInfo();
            handler.postDelayed(this, BATTERY_UPDATE_INTERVAL);
        }
    };

    private final Runnable shiftTextViewsRunnable = new Runnable() {
        @Override
        public void run() {
            shiftTextViews();
            handler.postDelayed(this, SHIFT_DURATION);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        instanceRef = new WeakReference<>(this);
        registerReceiver(songNameReceiver, new IntentFilter("com.bytesmith.daydream.SONG_NAME_UPDATED"));
        initializeDreamService();
    }

    private void initializeDreamService() {
        setInteractive(true);
        setFullscreen(true);
        setContentView(R.layout.activity_screensaver);
        adjustBrightness(10);
        setSystemUiVisibility();
        startNotificationService();
        initializeViews();
        initializeLayoutParams();
        loadShiftCountFromPreferences();
        incrementShiftCount();
        applyShiftPosition();
        updateBatteryInfo();
        updateTimeInWords();
        updateBatteryIconSize();
        updateDayDateTextView();
        
        // Check for active media notifications right away
        updateNotificationIcons(NotificationService.getNotificationPackages());
        
        // Request media notification info immediately to display current playing songs
        Intent intent = new Intent("com.bytesmith.daydream.REQUEST_MEDIA_INFO");
        sendBroadcast(intent);
        
        // Get AudioManager
        final android.media.AudioManager audioManager = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        
        // Set up key event handling for the window using WindowCallbackWrapper for simplicity (API 23+)
        final android.view.Window.Callback oldCallback = getWindow().getCallback();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().setCallback(new WindowCallbackWrapper(oldCallback) {
                @Override
                public boolean dispatchKeyEvent(android.view.KeyEvent event) {
                    int keyCode = event.getKeyCode();
                    int action = event.getAction();

                    // Handle volume keys on KeyDown event
                    if (action == android.view.KeyEvent.ACTION_DOWN) {
                        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                            Log.d(TAG, "Volume Up key pressed, adjusting volume (no UI) and consuming event.");
                            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                                        android.media.AudioManager.ADJUST_RAISE,
                                                        0);
                            return true; // Consume the event
                        } else if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                            Log.d(TAG, "Volume Down key pressed, adjusting volume (no UI) and consuming event.");
                            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                                        android.media.AudioManager.ADJUST_LOWER,
                                                        0);
                            return true; // Consume the event
                        }
                    }

                    // If it wasn't a volume key down event, let the default callback handle it
                    return super.dispatchKeyEvent(event);
                }

                @Override
                public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                    // Pass the event to the GestureDetector first
                    boolean handledByGestureDetector = gestureDetector.onTouchEvent(event);
                    Log.d(TAG, "dispatchTouchEvent: handledByDetector=" + handledByGestureDetector);

                    // If the GestureDetector handled it (e.g., double-tap), we consume it.
                    if (handledByGestureDetector) {
                        return true;
                    }

                    // Otherwise, let the original callback handle it (allows touch to pass to views like TextView)
                    return super.dispatchTouchEvent(event);
                }
            });
        } else {
             // Fallback for older APIs (keep original complex callback if needed, though unlikely given other API checks)
             // For brevity, assuming minSdk >= 23 based on other code. If not, the original anonymous class should be kept here.
             Log.w(TAG, "Window callback simplification requires API 23+. Using original callback.");
             // The original anonymous class implementation would go here as a fallback.
             // However, the original code also used API 23 checks, so this path is likely not needed.
             // Keeping the original complex anonymous inner class here for absolute safety if minSdk < 23:
             getWindow().setCallback(new android.view.Window.Callback() {
                 // --- Paste the entire original anonymous inner class code here ---
                 // (Removed for brevity in this example, but should be included if <23 support is critical)
                  @Override
                  public boolean dispatchKeyEvent(android.view.KeyEvent event) {
                      int keyCode = event.getKeyCode();
                      int action = event.getAction();

                      if (action == android.view.KeyEvent.ACTION_DOWN) {
                          if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                              Log.d(TAG, "Volume Up key pressed, adjusting volume (no UI) and consuming event.");
                              audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                                            android.media.AudioManager.ADJUST_RAISE,
                                                            0);
                              return true; // Consume the event
                          } else if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                              Log.d(TAG, "Volume Down key pressed, adjusting volume (no UI) and consuming event.");
                              audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                                            android.media.AudioManager.ADJUST_LOWER,
                                                            0);
                              return true; // Consume the event
                          }
                      }
                      return oldCallback != null && oldCallback.dispatchKeyEvent(event);
                  }
                   @Override
                  public boolean dispatchKeyShortcutEvent(android.view.KeyEvent event) { return oldCallback != null && oldCallback.dispatchKeyShortcutEvent(event); }
                   @Override
                  public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                      boolean handledByGestureDetector = gestureDetector.onTouchEvent(event);
                      Log.d(TAG, "dispatchTouchEvent: handledByDetector=" + handledByGestureDetector);
                      if (handledByGestureDetector) { return true; }
                      return oldCallback != null ? oldCallback.dispatchTouchEvent(event) : false;
                  }
                   @Override
                  public boolean dispatchTrackballEvent(android.view.MotionEvent event) { return oldCallback != null && oldCallback.dispatchTrackballEvent(event); }
                   @Override
                  public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) { return oldCallback != null && oldCallback.dispatchGenericMotionEvent(event); }
                   @Override
                  public boolean dispatchPopulateAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) { return oldCallback != null && oldCallback.dispatchPopulateAccessibilityEvent(event); }
                   @Override
                  public View onCreatePanelView(int featureId) { return oldCallback != null ? oldCallback.onCreatePanelView(featureId) : null; }
                   @Override
                  public boolean onCreatePanelMenu(int featureId, android.view.Menu menu) { return oldCallback != null && oldCallback.onCreatePanelMenu(featureId, menu); }
                   @Override
                  public boolean onPreparePanel(int featureId, View view, android.view.Menu menu) { return oldCallback != null && oldCallback.onPreparePanel(featureId, view, menu); }
                   @Override
                  public boolean onMenuOpened(int featureId, android.view.Menu menu) { return oldCallback != null && oldCallback.onMenuOpened(featureId, menu); }
                   @Override
                  public boolean onMenuItemSelected(int featureId, android.view.MenuItem item) { return oldCallback != null && oldCallback.onMenuItemSelected(featureId, item); }
                   @Override
                  public void onWindowAttributesChanged(android.view.WindowManager.LayoutParams attrs) { if (oldCallback != null) oldCallback.onWindowAttributesChanged(attrs); }
                   @Override
                  public void onContentChanged() { if (oldCallback != null) oldCallback.onContentChanged(); }
                   @Override
                  public void onWindowFocusChanged(boolean hasFocus) { if (oldCallback != null) oldCallback.onWindowFocusChanged(hasFocus); }
                   @Override
                  public void onAttachedToWindow() { if (oldCallback != null) oldCallback.onAttachedToWindow(); }
                   @Override
                  public void onDetachedFromWindow() { if (oldCallback != null) oldCallback.onDetachedFromWindow(); }
                   @Override
                  public void onPanelClosed(int featureId, android.view.Menu menu) { if (oldCallback != null) oldCallback.onPanelClosed(featureId, menu); }
                   @Override
                  public boolean onSearchRequested() { return oldCallback != null && oldCallback.onSearchRequested(); }
                   @Override
                  public boolean onSearchRequested(android.view.SearchEvent searchEvent) {
                      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) { return oldCallback != null && oldCallback.onSearchRequested(searchEvent); }
                      return false;
                  }
                   @Override
                  public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback) { return oldCallback != null ? oldCallback.onWindowStartingActionMode(callback) : null; }
                   @Override
                  public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback, int type) {
                      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) { return oldCallback != null ? oldCallback.onWindowStartingActionMode(callback, type) : null; }
                      return null;
                  }
                   @Override
                  public void onActionModeStarted(android.view.ActionMode mode) { if (oldCallback != null) oldCallback.onActionModeStarted(mode); }
                   @Override
                  public void onActionModeFinished(android.view.ActionMode mode) { if (oldCallback != null) oldCallback.onActionModeFinished(mode); }
             });
        }

        // --- Initialize GestureDetector --- 
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                Log.d(TAG, "Double tap detected, finishing DreamService.");
                finish(); // Exit the screensaver
                return true; // Indicate the double tap was handled
            }
            
             // Optional: If you want to consume other gestures like single tap 
             // without exiting, you might override other methods and return true.
             // For example, to consume single taps:
             /*
             @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                 Log.d(TAG, "Single tap consumed.");
                 return true;
             }
             */
        });
        // --------------------------------
    }

    private void setSystemUiVisibility() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                        View.SYSTEM_UI_FLAG_IMMERSIVE
        );
    }

    private void startNotificationService() {
        Intent intent = new Intent(this, NotificationService.class);
        startService(intent);
    }

    private void adjustBrightness(int brightnessLevel) {
        try {
            Log.d(TAG, "Attempting to change brightness to " + brightnessLevel + "%");
            BrightnessService.changeBrightness(this, brightnessLevel);
        } catch (Exception e) {
            Log.e(TAG, "Failed to change brightness", e);
        }
    }

    private void initializeViews() {
        timeInWordsTextView = findViewById(R.id.time_in_words);
        dayDateTextView = findViewById(R.id.day_date);
        batteryInfoTextView = findViewById(R.id.battery_info);
        batteryIconImageView = findViewById(R.id.battery_icon);
        songCountTextView = findViewById(R.id.song_count);
        songNameTextView = findViewById(R.id.song_name);
        notificationIconContainer = findViewById(R.id.notification_icon_container);
        
        // Initialize song name TextView as gone
        if (songNameTextView != null) {
            songNameTextView.setVisibility(View.GONE);
            Log.d(TAG, "Song name TextView initialized (GONE)");
        } else {
            Log.e(TAG, "Song name TextView is null");
        }
        
        // Make song count visible and set initial value
        if (songCountTextView != null) {
            // Set initial text and visibility based on current count
            if (songCount > 0) {
                songCountTextView.setText("Songs: " + songCount);
                songCountTextView.setVisibility(View.VISIBLE);
            } else {
                songCountTextView.setVisibility(View.GONE); 
            }
            Log.d(TAG, "Song count TextView initialized (Visibility: " + (songCountTextView.getVisibility() == View.VISIBLE ? "VISIBLE" : "GONE") + ")");
        }
    }

    private void initializeLayoutParams() {
        // Set layout parameters for time view
        setLayoutParams(timeInWordsTextView,
                R.dimen.margin_start_time_in_words,
                R.dimen.margin_top_time_in_words,
                R.dimen.margin_right_time_in_words,
                R.dimen.margin_bottom_time_in_words);

        // Set layout for day/date view below the time view
        RelativeLayout.LayoutParams dateLayoutParams = (RelativeLayout.LayoutParams) dayDateTextView.getLayoutParams();
        dateLayoutParams.addRule(RelativeLayout.BELOW, R.id.time_in_words);
        dateLayoutParams.setMargins(
                getResources().getDimensionPixelSize(R.dimen.margin_start_time_in_words),
                getResources().getDimensionPixelSize(R.dimen.margin_top_day_date),
                getResources().getDimensionPixelSize(R.dimen.margin_right_day_date),
                getResources().getDimensionPixelSize(R.dimen.margin_bottom_day_date)
        );
        dayDateTextView.setLayoutParams(dateLayoutParams);

        // Set layout for battery icon view below the day/date view
        RelativeLayout.LayoutParams batteryIconLayoutParams = (RelativeLayout.LayoutParams) batteryIconImageView.getLayoutParams();
        batteryIconLayoutParams.addRule(RelativeLayout.BELOW, R.id.day_date);
        batteryIconLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        batteryIconLayoutParams.setMargins(
                getResources().getDimensionPixelSize(R.dimen.margin_start_battery_icon),
                getResources().getDimensionPixelSize(R.dimen.margin_top_battery_icon),
                dpToPx(ICON_TEXT_SPACING_DP), // Use constant for right margin
                0
        );
        batteryIconImageView.setLayoutParams(batteryIconLayoutParams);

        // Set layout for battery info view to the right of battery icon
        RelativeLayout.LayoutParams batteryInfoLayoutParams = (RelativeLayout.LayoutParams) batteryInfoTextView.getLayoutParams();
        batteryInfoLayoutParams.addRule(RelativeLayout.BELOW, R.id.day_date);
        batteryInfoLayoutParams.addRule(RelativeLayout.END_OF, R.id.battery_icon);
        batteryInfoLayoutParams.addRule(RelativeLayout.ALIGN_TOP, R.id.battery_icon);
        batteryInfoLayoutParams.addRule(RelativeLayout.ALIGN_BOTTOM, R.id.battery_icon);
        batteryInfoLayoutParams.setMargins(
                dpToPx(ICON_TEXT_SPACING_DP), // Use constant for start margin
                0,
                getResources().getDimensionPixelSize(R.dimen.margin_right_battery_info),
                0
        );
        batteryInfoTextView.setLayoutParams(batteryInfoLayoutParams);
        // Add: Center the text vertically within the TextView's bounds
        batteryInfoTextView.setGravity(android.view.Gravity.CENTER_VERTICAL);

        RelativeLayout.LayoutParams songCountLayoutParams = (RelativeLayout.LayoutParams) songCountTextView.getLayoutParams();
        songCountLayoutParams.removeRule(RelativeLayout.ALIGN_BASELINE);
        songCountLayoutParams.addRule(RelativeLayout.END_OF, R.id.battery_info);
        songCountLayoutParams.addRule(RelativeLayout.ALIGN_TOP, R.id.battery_info);
        songCountLayoutParams.addRule(RelativeLayout.ALIGN_BOTTOM, R.id.battery_info);
        songCountLayoutParams.setMargins(
                getResources().getDimensionPixelSize(R.dimen.margin_start_song_count),
                0,
                0,
                0
        );
        songCountTextView.setLayoutParams(songCountLayoutParams);
        // Add: Center the text vertically within the TextView's bounds
        songCountTextView.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // --- Setup Click Listener for Song Name TextView --- 
        if (songNameTextView != null) {
            songNameTextView.setOnClickListener(v -> togglePlayPause());
        } else {
             Log.e(TAG, "Cannot set click listener, songNameTextView is null during init");
        }
        // --------------------------------------------------
    }

    private void setLayoutParams(View view, int marginStartRes, int marginTopRes, int marginRightRes, int marginBottomRes) {
        RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) view.getLayoutParams();
        layoutParams.setMargins(
                getResources().getDimensionPixelSize(marginStartRes),
                getResources().getDimensionPixelSize(marginTopRes),
                getResources().getDimensionPixelSize(marginRightRes),
                getResources().getDimensionPixelSize(marginBottomRes)
        );
        view.setLayoutParams(layoutParams);
    }

    private void updateBatteryIconSize() {
        batteryIconImageView.getLayoutParams().width = getResources().getDimensionPixelSize(R.dimen.battery_icon_width);
        batteryIconImageView.getLayoutParams().height = getResources().getDimensionPixelSize(R.dimen.battery_icon_height);
        batteryIconImageView.requestLayout();
    }

    private void loadShiftCountFromPreferences() {
        SharedPreferences preferences = getSharedPreferences("DreamServicePrefs", Context.MODE_PRIVATE);
        shiftCount = preferences.getInt("shiftCount", 0);
    }

    private void saveShiftCountToPreferences() {
        SharedPreferences preferences = getSharedPreferences("DreamServicePrefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("shiftCount", shiftCount);
        editor.apply();
    }

    private void startPeriodicUpdates() {
        if (isUpdating) return;
        
        isUpdating = true;
        handler.post(updateTimeRunnable);
        handler.post(updateDateRunnable);
        handler.post(updateSongCountRunnable);
        handler.post(updateBatteryRunnable);
        handler.post(shiftTextViewsRunnable);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        startPeriodicUpdates();
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        isUpdating = false;
        handler.removeCallbacksAndMessages(null);
        stopNotificationService();
        saveShiftCountToPreferences();
    }

    @Override
    public void onDetachedFromWindow() {
        isUpdating = false;
        handler.removeCallbacksAndMessages(null);
        stopNotificationService();
        restoreBrightness();
        unregisterReceiver(songNameReceiver);
        super.onDetachedFromWindow();
    }

    private void stopNotificationService() {
        Intent intent = new Intent(this, NotificationService.class);
        stopService(intent);
    }

    private void restoreBrightness() {
        Log.d(TAG, "Attempting to restore original brightness");
        BrightnessService.restoreOriginalBrightness(this);
    }

    // Update methods
    private void updateTimeInWords() {
        String timeInWords = getTimeInWords();
        timeInWordsTextView.setText(fromHtml(timeInWords));
    }

    @SuppressWarnings("deprecation")
    public static Spanned fromHtml(String html) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
        } else {
            return Html.fromHtml(html);
        }
    }

    private String getTimeInWords() {
        Calendar calendar = Calendar.getInstance();
        // Reuse the static formatter
        String timeKey = "time_" + TIME_WORDS_FORMAT.format(calendar.getTime()).replace(":", "_").toUpperCase();
        int resId = getResources().getIdentifier(timeKey, "string", getPackageName());
        return resId == 0 ? getString(R.string.default_time_string) : getString(resId);
    }

    private void updateDayDateTextView() {
        String dayDate = getDayDate();
        dayDateTextView.setText(dayDate);
    }

    private String getDayDate() {
        Calendar calendar = Calendar.getInstance();
        // Reuse the static formatter
        return DAY_DATE_FORMAT.format(calendar.getTime());
    }

    private void updateBatteryInfo() {
        int batteryLevel = getBatteryLevel();
        batteryInfoTextView.setText(batteryLevel + "%");
        int batteryIconResId = getBatteryIconResId(batteryLevel);
        batteryIconImageView.setImageResource(batteryIconResId);
    }

    private int getBatteryLevel() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
        int scale = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
        return level == -1 || scale == -1 ? 0 : (int) ((level / (float) scale) * 100);
    }

    private int getBatteryIconResId(int batteryLevel) {
        int[] thresholds = {100, 95, 90, 85, 80, 75, 70, 65, 60, 55, 50, 45, 40, 35, 30, 25, 20, 15, 10, 5};
        int[] resIds = {
                R.drawable.battery_charging_100,
                R.drawable.battery_charging_95,
                R.drawable.battery_charging_90,
                R.drawable.battery_charging_85,
                R.drawable.battery_charging_80,
                R.drawable.battery_charging_75,
                R.drawable.battery_charging_70,
                R.drawable.battery_charging_65,
                R.drawable.battery_charging_60,
                R.drawable.battery_charging_55,
                R.drawable.battery_charging_50,
                R.drawable.battery_charging_45,
                R.drawable.battery_charging_40,
                R.drawable.battery_charging_35,
                R.drawable.battery_charging_30,
                R.drawable.battery_charging_25,
                R.drawable.battery_charging_20,
                R.drawable.battery_charging_15,
                R.drawable.battery_charging_10,
                R.drawable.battery_charging_5
        };

        for (int i = 0; i < thresholds.length; i++) {
            if (batteryLevel >= thresholds[i]) {
                return resIds[i];
            }
        }
        return R.drawable.battery_charging_1;
    }

    private void updateNotificationIcons(Set<String> notificationPackages) {
        if (notificationIconContainer == null) {
            Log.e(TAG, "notificationIconContainer is null, cannot update icons.");
            return;
        }
        
        // Get the package currently playing media (if any)
        String playingPackage = NotificationService.getCurrentlyPlayingMediaPackage();
        Log.d(TAG, "Currently playing media package: " + (playingPackage != null ? playingPackage : "None"));

        notificationIconContainer.post(() -> {
            notificationIconContainer.removeAllViews();
            if (notificationPackages == null || notificationPackages.isEmpty()) {
                return;
            }

            Log.d(TAG, "Updating icons. Total packages: " + notificationPackages.size() + ". Excluding: " + playingPackage);
            for (String packageName : notificationPackages) {
                // *** Skip icon if it's the currently playing media app ***
                if (packageName.equals(playingPackage)) {
                    Log.d(TAG, "Skipping icon for currently playing package: " + packageName);
                    continue; // Skip this package
                }
                
                Drawable icon = fetchAndCacheNotificationIcon(packageName);
                if (icon != null) {
                    ImageView iconView = new ImageView(DreamService.this);
                    iconView.setImageDrawable(icon);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            getResources().getDimensionPixelSize(R.dimen.notification_icon_size),
                            getResources().getDimensionPixelSize(R.dimen.notification_icon_size)
                    );
                    params.setMargins(0, 0, 
                            getResources().getDimensionPixelSize(R.dimen.notification_icon_margin), 0);
                    iconView.setLayoutParams(params);
                    notificationIconContainer.addView(iconView);
                } else {
                    Log.w(TAG, "Could not fetch icon for package: " + packageName);
                }
            }
        });
    }

    private Drawable fetchAndCacheNotificationIcon(String packageName) {
        try {
            Drawable icon = getPackageManager().getApplicationIcon(packageName);
            cacheIcon(packageName, icon);
            return icon;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to get icon for package: " + packageName, e);
            return null;
        }
    }

    private void cacheIcon(String packageName, Drawable icon) {
        if (iconCacheDir == null) {
            iconCacheDir = new File(getCacheDir(), "icon_cache");
            if (!iconCacheDir.exists() && !iconCacheDir.mkdirs()) {
                Log.e(TAG, "Failed to create icon cache directory");
                return;
            }
        }
        File iconFile = new File(iconCacheDir, packageName + ".png");
        if (iconFile.exists()) {
            return;
        }
        if (icon instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) icon).getBitmap();
            try (FileOutputStream fos = new FileOutputStream(iconFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } catch (IOException e) {
                Log.e(TAG, "Failed to cache icon for package: " + packageName, e);
            }
        }
    }

    private void shiftTextViews() {
        incrementShiftCount();
        applyShiftPosition();
    }

    private void incrementShiftCount() {
        shiftCount = (shiftCount + 1) % MAX_SHIFTS;
    }

    private void applyShiftPosition() {
        int shiftAmount = shiftCount * SHIFT_AMOUNT;
        // Adjust only the top margin for the time TextView
        RelativeLayout.LayoutParams timeLayoutParams = (RelativeLayout.LayoutParams) timeInWordsTextView.getLayoutParams();

        // Check if layoutParams is null before accessing properties
        if (timeLayoutParams != null) {
             timeLayoutParams.topMargin = shiftAmount;
             timeInWordsTextView.setLayoutParams(timeLayoutParams);
             // No need to reset margins for other views as their relative positions
             // defined by rules (BELOW, END_OF) should remain correct.
        } else {
            Log.e(TAG, "TimeInWordsTextView layout params are null in applyShiftPosition");
        }
    }

    private final BroadcastReceiver songNameReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.bytesmith.daydream.SONG_NAME_UPDATED".equals(intent.getAction())) {
                String songName = intent.getStringExtra("songName");
                Log.d(TAG, "Received broadcast with song name: " + (songName != null ? songName : "null"));
                if (songName != null && !songName.isEmpty()) {
                    Log.d(TAG, "Received song name via broadcast: " + songName);
                    updateSongInfo(songName, null);
                }
            }
        }
    };

    /**
     * Updates the song name displayed in the dream UI.
     * This method can be called from any thread and will update the UI on the main thread.
     *
     * @param songName The name of the song to display, or null if no song is playing
     * @param token The MediaSession.Token associated with the media player
     */
    public static void updateSongInfo(@Nullable String songName, @Nullable MediaSession.Token token) {
        DreamService instance = instanceRef == null ? null : instanceRef.get();
        if (instance == null || instance.handler == null) {
            Log.e(TAG, "DreamService instance or handler is null in updateSongInfo");
            return;
        }
        
        instance.handler.post(() -> {
            TextView textView = instance.findViewById(R.id.song_name);
            if (textView == null) {
                Log.e(TAG, "Song name TextView is null in updateSongInfo runnable");
                return;
            }
            
            Log.d(TAG, "updateSongInfo received: " + (songName != null ? songName : "null") + ", Token: " + (token != null));

            // Update MediaController
            synchronized (controllerLock) {
                if (token == null) {
                    currentMediaController = null;
                } else {
                    try {
                        // Check if token is different before creating new controller
                        if (currentMediaController == null || !token.equals(currentMediaController.getSessionToken())) {
                             Log.d(TAG, "Creating new MediaController from token.");
                             currentMediaController = new MediaController(instance, token);
                        } else {
                             Log.d(TAG, "Using existing MediaController.");
                        }
                    } catch (Exception e) { // Catch potential SecurityException or others
                        Log.e(TAG, "Error creating MediaController from token", e);
                        currentMediaController = null;
                    }
                }
            }

            // Update TextView
            if (songName == null || songName.isEmpty()) {
                textView.setVisibility(View.INVISIBLE);
            } else {
                // Format with HTML (optimized string building)
                Spanned formattedText;
                int newlineIndex = songName.indexOf('\\n'); // Use single quote for char
                if (newlineIndex != -1) {
                    String title = songName.substring(0, newlineIndex);
                    String artist = songName.substring(newlineIndex + 1);
                    // Use StringBuilder for slightly cleaner concatenation (though + is often optimized)
                    String htmlString = new StringBuilder()
                            .append("<font size=\"+4\"><b>")
                            .append(title)
                            .append("</b></font><br>")
                            .append(artist)
                            .toString();
                    formattedText = DreamService.fromHtml(htmlString);
                } else {
                    // Only one line exists, make it bold and slightly larger
                    String htmlString = new StringBuilder()
                            .append("<font size=\"+4\"><b>")
                            .append(songName)
                            .append("</b></font>")
                            .toString();
                    formattedText = DreamService.fromHtml(htmlString);
                }
                textView.setText(formattedText);
                textView.setVisibility(View.VISIBLE);
                
                // Restore Gravity setting for center alignment
                textView.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                
                // Restore other styling (size, linespacing, shadow, color) if needed
                // textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
                // textView.setLineSpacing(10f, 1.0f); 
                // textView.setShadowLayer(3, 1, 1, Color.BLACK);
                // textView.setTextColor(Color.WHITE);
                // textView.bringToFront(); // If needed
            }
        });
    }

    @SuppressLint("SetTextI18n")
    public static void updateSongCount(final int count) {
        Log.d(TAG, "updateSongCount called with: " + count);
        songCount = count; // Update static variable
        final DreamService service = instanceRef != null ? instanceRef.get() : null;
        if (service == null) {
            Log.e(TAG, "Cannot update song count - service instance is null");
            return;
        }
        if (service.songCountTextView == null) {
             Log.e(TAG, "Cannot update song count - songCountTextView is null");
             return;
        }
        
        service.handler.post(() -> {
            if (service.songCountTextView != null) {
                if (count > 0) {
                    Log.d(TAG, "Setting song count text to: Songs: " + count);
                    service.songCountTextView.setText("Songs: " + count);
                    service.songCountTextView.setVisibility(View.VISIBLE);
                } else {
                    Log.d(TAG, "Setting song count visibility to GONE as count is 0");
                    service.songCountTextView.setVisibility(View.GONE);
                }
            } else {
                Log.e(TAG, "songCountTextView became null during handler execution");
            }
        });
    }

    // --- Play/Pause Toggle Logic --- 
    private void togglePlayPause() {
        synchronized (controllerLock) {
            if (currentMediaController == null) {
                Log.d(TAG, "togglePlayPause: No active MediaController.");
                return;
            }

            PlaybackState state = currentMediaController.getPlaybackState();
            if (state == null) {
                Log.d(TAG, "togglePlayPause: PlaybackState is null.");
                 // Try playing anyway as a fallback?
                 currentMediaController.getTransportControls().play();
                return;
            }

            int currentState = state.getState();
            Log.d(TAG, "togglePlayPause: Current state = " + currentState);

            if (currentState == PlaybackState.STATE_PLAYING || 
                currentState == PlaybackState.STATE_BUFFERING) {
                Log.d(TAG, "Pausing media.");
                currentMediaController.getTransportControls().pause();
            } else if (currentState == PlaybackState.STATE_PAUSED || 
                       currentState == PlaybackState.STATE_STOPPED || 
                       currentState == PlaybackState.STATE_NONE) {
                Log.d(TAG, "Playing media.");
                currentMediaController.getTransportControls().play();
            } else {
                 Log.d(TAG, "MediaController in unhandled state: " + currentState);
            }
        }
    }
    // ----------------------------- 

    // Helper function to convert dp to pixels
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
    }
}