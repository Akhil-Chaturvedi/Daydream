package com.bytesmith.daydream;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import java.lang.ref.WeakReference;

public class BrightnessService {

    private static final String TAG = "BrightnessService";
    // Use a volatile variable to ensure visibility in a multi-threaded environment.
    private static volatile float originalBrightness = -1;
    private static WeakReference<Context> contextRef;

    /**
     * Changes the system brightness to the given value (0-100).
     *
     * @param context    Application context.
     * @param brightnessPercent New brightness value as percentage (0-100).
     */
    public static synchronized void changeBrightness(Context context, int brightnessPercent) {
        if (context == null) return;
        
        // Store a weak reference to the context to avoid leaks
        contextRef = new WeakReference<>(context.getApplicationContext());
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(context)) {
                Log.e(TAG, "WRITE_SETTINGS permission not granted");
                return;
            }
        }

        try {
            // Convert percentage (0-100) to system brightness value (0-255)
            int brightness = Math.round(brightnessPercent * 2.55f);
            
            // Validate brightness bounds (0-255)
            brightness = Math.max(0, Math.min(255, brightness));

            // Save the original brightness only once
            if (originalBrightness == -1) {
                originalBrightness = getCurrentBrightness(context);
                Log.d(TAG, "Original brightness saved: " + originalBrightness);
            }

            // Only change brightness if the desired brightness differs from current
            int currentBrightness = getCurrentBrightness(context);
            if (currentBrightness != brightness) {
                Settings.System.putInt(
                        context.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS,
                        brightness
                );
                Log.d(TAG, "Changed brightness from " + currentBrightness + " to " + brightness);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to change brightness", e);
        }
    }

    /**
     * Restores the system brightness to the original value.
     */
    public static synchronized void restoreOriginalBrightness(Context context) {
        // Simplified context handling
        Context appContext = null;
        if (context != null) {
            appContext = context.getApplicationContext();
        } else if (contextRef != null) {
            appContext = contextRef.get();
        }
        
        if (appContext == null) {
            Log.e(TAG, "Cannot restore brightness - no context available");
            // Clear context reference if it exists but is null
            if (contextRef != null) {
                contextRef.clear();
            }
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(appContext)) {
                Log.e(TAG, "WRITE_SETTINGS permission not granted, cannot restore");
                // Clear context reference even if permission denied
                if (contextRef != null) {
                    contextRef.clear();
                }
                return;
            }
        }

        try {
            if (originalBrightness != -1) {
                int restoredBrightness = (int) originalBrightness; // Keep cast here as original was stored as float
                Settings.System.putInt(
                        appContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS,
                        restoredBrightness
                );
                Log.d(TAG, "Restored brightness to " + restoredBrightness);
                // Reset originalBrightness to indicate that it's been restored.
                originalBrightness = -1;
            } else {
                Log.d(TAG, "Original brightness not saved; nothing to restore.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore original brightness", e);
        }
        
        // Clear context reference to prevent potential leaks
        if (contextRef != null) {
            contextRef.clear();
        }
    }

    /**
     * Retrieves the current system brightness.
     *
     * @param context Application context.
     * @return The current brightness value (0-255).
     */
    private static int getCurrentBrightness(Context context) {
        try {
            return Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS
            );
        } catch (Settings.SettingNotFoundException e) {
            Log.e(TAG, "Failed to get current brightness", e);
            return 128; // Default to mid-brightness if setting not found
        }
    }
}