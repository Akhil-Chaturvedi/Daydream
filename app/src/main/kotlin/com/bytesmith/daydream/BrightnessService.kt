package com.bytesmith.daydream
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.lang.ref.WeakReference
import kotlin.math.roundToInt
// In Kotlin, a class with only static members is best represented as an 'object'.
// This creates a thread-safe singleton instance.
object BrightnessService {
    private const val TAG = "BrightnessService"
    // @Volatile ensures visibility in a multi-threaded environment.
    @Volatile
    private var originalBrightness: Float = -1f // Use 'var' for mutable variables
    private var contextRef: WeakReference<Context>? = null // Nullable type
    /**
     * Changes the system brightness to the given value (0-100).
     *
     * @param context    Application context.
     * @param brightnessPercent New brightness value as percentage (0-100).
     */
    @Synchronized // The @Synchronized annotation replaces the 'synchronized' keyword
    fun changeBrightness(context: Context?, brightnessPercent: Int) {
        // Use the elvis operator '?:' for a concise null check
        context ?: return
        // Store a weak reference to the context to avoid leaks
        contextRef = WeakReference(context.applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(context)) {
                Log.e(TAG, "WRITE_SETTINGS permission not granted")
                return
            }
        }
        try {
            // Convert percentage (0-100) to system brightness value (0-255)
            // .coerceIn is an idiomatic Kotlin way to clamp a value within a range.
            val brightness = (brightnessPercent * 2.55f).roundToInt().coerceIn(0, 255)
            // Save the original brightness only once
            if (originalBrightness == -1f) {
                originalBrightness = getCurrentBrightness(context).toFloat()
                // Use string templates for logging
                Log.d(TAG, "Original brightness saved: $originalBrightness")
            }
            // Only change brightness if the desired brightness differs from current
            val currentBrightness = getCurrentBrightness(context)
            if (currentBrightness != brightness) {
                Settings.System.putInt(
                    context.contentResolver, // Use property access syntax
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightness
                )
                Log.d(TAG, "Changed brightness from $currentBrightness to $brightness")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to change brightness", e)
        }
    }
    /**
     * Restores the system brightness to the original value.
     */
    @Synchronized
    fun restoreOriginalBrightness(context: Context?) {
        // Simplified context handling with the elvis operator
        val appContext = context?.applicationContext ?: contextRef?.get()
        if (appContext == null) {
            Log.e(TAG, "Cannot restore brightness - no context available")
            contextRef?.clear()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(appContext)) {
                Log.e(TAG, "WRITE_SETTINGS permission not granted, cannot restore")
                contextRef?.clear()
                return
            }
        }
        try {
            if (originalBrightness != -1f) {
                val restoredBrightness = originalBrightness.toInt()
                Settings.System.putInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    restoredBrightness
                )
                Log.d(TAG, "Restored brightness to $restoredBrightness")
                // Reset originalBrightness to indicate that it's been restored.
                originalBrightness = -1f
            } else {
                Log.d(TAG, "Original brightness not saved; nothing to restore.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore original brightness", e)
        } finally {
            // A finally block ensures this runs even if an exception occurs
            contextRef?.clear()
        }
    }
    /**
     * Retrieves the current system brightness.
     *
     * @param context Application context.
     * @return The current brightness value (0-255).
     */
    private fun getCurrentBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (e: Settings.SettingNotFoundException) {
            Log.e(TAG, "Failed to get current brightness", e)
            128 // Default to mid-brightness if setting not found
        }
    }
}