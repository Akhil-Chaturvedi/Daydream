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
import android.graphics.Color;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.view.KeyEvent;
import android.media.AudioManager;

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

public class DreamService extends android.service.dreams.DreamService {

    private static final String TAG = "DreamService";
    private static final long UPDATE_INTERVAL = 1000; // 1 second for time and date updates
    private static final long BATTERY_UPDATE_INTERVAL = 60000; // 1 minute for battery updates
    private static final long SHIFT_DURATION = 10000; // 10 seconds for text shifting
    private static final int SHIFT_AMOUNT = 100; // Pixel shift amount
    private static final int MAX_SHIFTS = 5;

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
    private AudioManager audioManager;

    // Reusable formatters
    private static final SimpleDateFormat TIME_WORDS_FORMAT = new SimpleDateFormat("hh_mm_a", Locale.getDefault());
    private static final SimpleDateFormat DAY_DATE_FORMAT = new SimpleDateFormat("EEEE, MMM d", Locale.getDefault());

    @Override
    public void onCreate() {
        super.onCreate();
        instanceRef = new WeakReference<>(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
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

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        instanceRef = new WeakReference<>(this);
        registerReceiver(songNameReceiver, new IntentFilter("com.bytesmith.daydream.SONG_NAME_UPDATED"));
        initializeDreamService();

        // Initialize GestureDetector
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                Log.d(TAG, "Double tap detected, finishing DreamService.");
                finish(); // Finish the dream on double tap
                return true; // Consume the event
            }
        });

        // Set OnTouchListener on the root view to detect gestures
        getWindow().getDecorView().setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            // Return true to indicate the event was handled if the gesture detector handled it.
            // Returning false allows other touch handling (like simple interaction) to occur.
            // Let's return true to prioritize the double-tap exit.
            return true;
        });
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
                0, 
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
                0,
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
        // Clean up listener to prevent memory leaks
        getWindow().getDecorView().setOnTouchListener(null);
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
                    updateSongInfo(songName);
                }
            }
        }
    };

    /**
     * Updates the song name displayed in the dream UI.
     * This method can be called from any thread and will update the UI on the main thread.
     *
     * @param songName The name of the song to display, or null if no song is playing
     */
    public static void updateSongInfo(@Nullable String songName) {
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
            
            Log.d(TAG, "updateSongInfo received: " + (songName != null ? songName : "null"));

            // Update TextView
            if (songName == null || songName.isEmpty()) {
                textView.setVisibility(View.INVISIBLE);
            } else {
                // Format with HTML (optimized string building)
                Spanned formattedText;
                int newlineIndex = songName.indexOf('\n');
                if (newlineIndex != -1) {
                    String title = songName.substring(0, newlineIndex);
                    String artist = songName.substring(newlineIndex + 1);

                    // *** Capitalize Title and Artist ***
                    String capitalizedTitle = capitalizeWords(title);
                    String capitalizedArtist = capitalizeWords(artist);
                    // **********************************

                    String htmlString = new StringBuilder()
                            .append("<font size=\"+4\"><b>")
                            .append(capitalizedTitle) // Use capitalized version
                            .append("</b></font><br>")
                            .append(capitalizedArtist) // Use capitalized version
                            .toString();
                    formattedText = DreamService.fromHtml(htmlString);
                } else {
                    // Only one line exists, treat as title
                    // *** Capitalize Song Name (Title) ***
                    String capitalizedTitle = capitalizeWords(songName);
                    // ***********************************

                    String htmlString = new StringBuilder()
                            .append("<font size=\"+4\"><b>")
                            .append(capitalizedTitle) // Use capitalized version
                            .append("</b></font>")
                            .toString();
                    formattedText = DreamService.fromHtml(htmlString);
                }
                textView.setText(formattedText);
                textView.setVisibility(View.VISIBLE);
                
                textView.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            }
        });
    }

    // *** Add Helper function to capitalize words ***
    private static String capitalizeWords(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        String[] words = str.split("\\s+");
        StringBuilder capitalizedString = new StringBuilder();
        for (String word : words) {
            if (word.length() > 0) {
                capitalizedString.append(Character.toUpperCase(word.charAt(0)))
                                 .append(word.substring(1).toLowerCase())
                                 .append(" ");
            }
        }
        return capitalizedString.toString().trim(); // Trim trailing space
    }
    // ********************************************

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

    // Helper function to convert dp to pixels
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
    }

    // Override dispatchKeyEvent to handle volume buttons
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        int keyCode = event.getKeyCode();

        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (action == KeyEvent.ACTION_DOWN) {
                    Log.d(TAG, "Volume Up Pressed - Adjusting volume manually");
                    if (audioManager != null) {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_RAISE,
                                0); // 0 flags = no UI shown by default handler
                    }
                }
                return true; // Consume the event to prevent system UI
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (action == KeyEvent.ACTION_DOWN) {
                    Log.d(TAG, "Volume Down Pressed - Adjusting volume manually");
                    if (audioManager != null) {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_LOWER,
                                0); // 0 flags = no UI shown by default handler
                    }
                }
                return true; // Consume the event to prevent system UI
            default:
                // For all other keys, let the default system handling occur
                return super.dispatchKeyEvent(event);
        }
    }
}