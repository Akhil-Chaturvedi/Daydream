package com.bytesmith.daydream

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.lang.ref.WeakReference

/**
 * Centralized settings manager for all Daydream configurable values.
 * Provides getters for all 54+ settings with sensible defaults matching original hardcoded behavior.
 * Supports listeners for live preview updates.
 * 
 * Refactored to reduce boilerplate using typed setting classes.
 */
object DaydreamSettings {

    private const val PREFS_NAME = "daydream_settings"

    private var prefsRef: WeakReference<SharedPreferences>? = null
    private val listeners = mutableListOf<(String) -> Unit>()

    private fun getPrefs(context: Context): SharedPreferences {
        return prefsRef?.get() ?: context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefsRef = WeakReference(it) }
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(key: String) {
        listeners.forEach { it(key) }
    }

    // ==================== ANIMATION ====================

    object Animation {
        private const val PREFIX = "anim_"
        
        const val KEY_TRANSITION_OUT_MS = "${PREFIX}transition_out_ms"
        const val KEY_TRANSITION_IN_MS = "${PREFIX}transition_in_ms"
        const val KEY_FADE_OUT_K = "${PREFIX}fade_out_k"
        const val KEY_FADE_IN_K = "${PREFIX}fade_in_k"
        const val KEY_SCALE_ANIM_DURATION_MS = "${PREFIX}scale_duration_ms"
        const val KEY_FRAME_DELAY_MS = "${PREFIX}frame_delay_ms"

        const val DEFAULT_TRANSITION_OUT_MS = 700L
        const val DEFAULT_TRANSITION_IN_MS = 800L
        const val DEFAULT_FADE_OUT_K = 4.0f
        const val DEFAULT_FADE_IN_K = 2.5f
        const val DEFAULT_SCALE_ANIM_DURATION_MS = 300L
        const val DEFAULT_FRAME_DELAY_MS = 16L

        fun getTransitionOutMs(context: Context): Long = 
            getPrefs(context).getLong(KEY_TRANSITION_OUT_MS, DEFAULT_TRANSITION_OUT_MS)
        fun setTransitionOutMs(context: Context, value: Long) {
            getPrefs(context).edit { putLong(KEY_TRANSITION_OUT_MS, value) }
            notifyListeners(KEY_TRANSITION_OUT_MS)
        }

        fun getTransitionInMs(context: Context): Long = 
            getPrefs(context).getLong(KEY_TRANSITION_IN_MS, DEFAULT_TRANSITION_IN_MS)
        fun setTransitionInMs(context: Context, value: Long) {
            getPrefs(context).edit { putLong(KEY_TRANSITION_IN_MS, value) }
            notifyListeners(KEY_TRANSITION_IN_MS)
        }

        fun getFadeOutK(context: Context): Float = 
            getPrefs(context).getFloat(KEY_FADE_OUT_K, DEFAULT_FADE_OUT_K)
        fun setFadeOutK(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_FADE_OUT_K, value) }
            notifyListeners(KEY_FADE_OUT_K)
        }

        fun getFadeInK(context: Context): Float = 
            getPrefs(context).getFloat(KEY_FADE_IN_K, DEFAULT_FADE_IN_K)
        fun setFadeInK(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_FADE_IN_K, value) }
            notifyListeners(KEY_FADE_IN_K)
        }

        fun getScaleAnimDurationMs(context: Context): Long = 
            getPrefs(context).getLong(KEY_SCALE_ANIM_DURATION_MS, DEFAULT_SCALE_ANIM_DURATION_MS)
        fun setScaleAnimDurationMs(context: Context, value: Long) {
            getPrefs(context).edit { putLong(KEY_SCALE_ANIM_DURATION_MS, value) }
            notifyListeners(KEY_SCALE_ANIM_DURATION_MS)
        }

        fun getFrameDelayMs(context: Context): Long = 
            getPrefs(context).getLong(KEY_FRAME_DELAY_MS, DEFAULT_FRAME_DELAY_MS)
        fun setFrameDelayMs(context: Context, value: Long) {
            getPrefs(context).edit { putLong(KEY_FRAME_DELAY_MS, value) }
            notifyListeners(KEY_FRAME_DELAY_MS)
        }
    }

    // ==================== TIMING ====================

    object Timing {
        private const val PREFIX = "timing_"
        
        const val KEY_BATTERY_UPDATE_INTERVAL_MS = "${PREFIX}battery_interval_ms"
        const val KEY_MEDIA_SESSION_CHECK_INTERVAL_MS = "${PREFIX}media_check_ms"
        const val KEY_MIN_PLAYBACK_DURATION_MS = "${PREFIX}min_playback_ms"
        const val KEY_MAX_PLAYBACK_DURATION_MS = "${PREFIX}max_playback_ms"
        const val KEY_LAYOUT_DELAY_MS = "${PREFIX}layout_delay_ms"
        const val KEY_POST_ANIMATION_DELAY_MS = "${PREFIX}post_anim_delay_ms"
        const val KEY_SAFETY_WAIT_MS = "${PREFIX}safety_wait_ms"

        const val DEFAULT_BATTERY_UPDATE_INTERVAL_MS = 60000L
        const val DEFAULT_MEDIA_SESSION_CHECK_INTERVAL_MS = 5000L
        const val DEFAULT_MIN_PLAYBACK_DURATION_MS = 30000L
        const val DEFAULT_MAX_PLAYBACK_DURATION_MS = 600000L
        const val DEFAULT_LAYOUT_DELAY_MS = 100L
        const val DEFAULT_POST_ANIMATION_DELAY_MS = 2000L
        const val DEFAULT_SAFETY_WAIT_MS = 10L

        fun getBatteryUpdateIntervalMs(context: Context): Long = 
            getPrefs(context).getLong(KEY_BATTERY_UPDATE_INTERVAL_MS, DEFAULT_BATTERY_UPDATE_INTERVAL_MS)
        fun setBatteryUpdateIntervalMs(context: Context, value: Long) {
            getPrefs(context).edit { putLong(KEY_BATTERY_UPDATE_INTERVAL_MS, value) }
            notifyListeners(KEY_BATTERY_UPDATE_INTERVAL_MS)
        }

        fun getMediaSessionCheckIntervalMs(context: Context): Long = 
            getPrefs(context).getLong(KEY_MEDIA_SESSION_CHECK_INTERVAL_MS, DEFAULT_MEDIA_SESSION_CHECK_INTERVAL_MS)
        fun setMediaSessionCheckIntervalMs(context: Context, value: Long) {
            getPrefs(context).edit { putLong(KEY_MEDIA_SESSION_CHECK_INTERVAL_MS, value) }
            notifyListeners(KEY_MEDIA_SESSION_CHECK_INTERVAL_MS)
        }

        fun getMinPlaybackDurationMs(context: Context): Long = 
            getPrefs(context).getLong(KEY_MIN_PLAYBACK_DURATION_MS, DEFAULT_MIN_PLAYBACK_DURATION_MS)
        fun setMinPlaybackDurationMs(context: Context, value: Long) {
            getPrefs(context).edit { putLong(KEY_MIN_PLAYBACK_DURATION_MS, value) }
            notifyListeners(KEY_MIN_PLAYBACK_DURATION_MS)
        }

        fun getMaxPlaybackDurationMs(context: Context): Long = 
            getPrefs(context).getLong(KEY_MAX_PLAYBACK_DURATION_MS, DEFAULT_MAX_PLAYBACK_DURATION_MS)
        fun setMaxPlaybackDurationMs(context: Context, value: Long) {
            getPrefs(context).edit { putLong(KEY_MAX_PLAYBACK_DURATION_MS, value) }
            notifyListeners(KEY_MAX_PLAYBACK_DURATION_MS)
        }

        fun getLayoutDelayMs(context: Context): Long = 
            getPrefs(context).getLong(KEY_LAYOUT_DELAY_MS, DEFAULT_LAYOUT_DELAY_MS)
        fun setLayoutDelayMs(context: Context, value: Long) {
            getPrefs(context).edit { putLong(KEY_LAYOUT_DELAY_MS, value) }
            notifyListeners(KEY_LAYOUT_DELAY_MS)
        }

        fun getPostAnimationDelayMs(context: Context): Long = 
            getPrefs(context).getLong(KEY_POST_ANIMATION_DELAY_MS, DEFAULT_POST_ANIMATION_DELAY_MS)
        fun setPostAnimationDelayMs(context: Context, value: Long) {
            getPrefs(context).edit { putLong(KEY_POST_ANIMATION_DELAY_MS, value) }
            notifyListeners(KEY_POST_ANIMATION_DELAY_MS)
        }

        fun getSafetyWaitMs(context: Context): Long = 
            getPrefs(context).getLong(KEY_SAFETY_WAIT_MS, DEFAULT_SAFETY_WAIT_MS)
        fun setSafetyWaitMs(context: Context, value: Long) {
            getPrefs(context).edit { putLong(KEY_SAFETY_WAIT_MS, value) }
            notifyListeners(KEY_SAFETY_WAIT_MS)
        }
    }

    // ==================== LAYOUT ====================

    object Layout {
        private const val PREFIX = "layout_"
        
        const val KEY_CONTENT_SCALE_PERCENT = "${PREFIX}scale_percent"
        const val KEY_MAX_WIDTH_RATIO = "${PREFIX}max_width_ratio"
        const val KEY_BOTTOM_BUFFER_RATIO = "${PREFIX}bottom_buffer_ratio"
        const val KEY_MEMORY_CACHE_DIVISOR = "${PREFIX}memory_cache_divisor"

        const val DEFAULT_CONTENT_SCALE_PERCENT = 0 // 0 = auto
        const val DEFAULT_MAX_WIDTH_RATIO = 0.98f
        const val DEFAULT_BOTTOM_BUFFER_RATIO = 0.05f
        const val DEFAULT_MEMORY_CACHE_DIVISOR = 8

        fun getContentScalePercent(context: Context): Int = 
            getPrefs(context).getInt(KEY_CONTENT_SCALE_PERCENT, DEFAULT_CONTENT_SCALE_PERCENT)
        fun setContentScalePercent(context: Context, value: Int) {
            getPrefs(context).edit { putInt(KEY_CONTENT_SCALE_PERCENT, value) }
            notifyListeners(KEY_CONTENT_SCALE_PERCENT)
        }

        fun getMaxWidthRatio(context: Context): Float = 
            getPrefs(context).getFloat(KEY_MAX_WIDTH_RATIO, DEFAULT_MAX_WIDTH_RATIO)
        fun setMaxWidthRatio(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_MAX_WIDTH_RATIO, value) }
            notifyListeners(KEY_MAX_WIDTH_RATIO)
        }

        fun getBottomBufferRatio(context: Context): Float = 
            getPrefs(context).getFloat(KEY_BOTTOM_BUFFER_RATIO, DEFAULT_BOTTOM_BUFFER_RATIO)
        fun setBottomBufferRatio(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_BOTTOM_BUFFER_RATIO, value) }
            notifyListeners(KEY_BOTTOM_BUFFER_RATIO)
        }

        fun getMemoryCacheDivisor(context: Context): Int = 
            getPrefs(context).getInt(KEY_MEMORY_CACHE_DIVISOR, DEFAULT_MEMORY_CACHE_DIVISOR)
        fun setMemoryCacheDivisor(context: Context, value: Int) {
            getPrefs(context).edit { putInt(KEY_MEMORY_CACHE_DIVISOR, value) }
            notifyListeners(KEY_MEMORY_CACHE_DIVISOR)
        }
    }

    // ==================== GESTURES ====================

    object Gestures {
        private const val PREFIX = "gesture_"
        
        const val KEY_SWIPE_THRESHOLD = "${PREFIX}swipe_threshold"
        const val KEY_SWIPE_VELOCITY_THRESHOLD = "${PREFIX}swipe_velocity"

        const val DEFAULT_SWIPE_THRESHOLD = 100
        const val DEFAULT_SWIPE_VELOCITY_THRESHOLD = 100

        fun getSwipeThreshold(context: Context): Int = 
            getPrefs(context).getInt(KEY_SWIPE_THRESHOLD, DEFAULT_SWIPE_THRESHOLD)
        fun setSwipeThreshold(context: Context, value: Int) {
            getPrefs(context).edit { putInt(KEY_SWIPE_THRESHOLD, value) }
            notifyListeners(KEY_SWIPE_THRESHOLD)
        }

        fun getSwipeVelocityThreshold(context: Context): Int = 
            getPrefs(context).getInt(KEY_SWIPE_VELOCITY_THRESHOLD, DEFAULT_SWIPE_VELOCITY_THRESHOLD)
        fun setSwipeVelocityThreshold(context: Context, value: Int) {
            getPrefs(context).edit { putInt(KEY_SWIPE_VELOCITY_THRESHOLD, value) }
            notifyListeners(KEY_SWIPE_VELOCITY_THRESHOLD)
        }
    }

    // ==================== APPEARANCE ====================

    object Appearance {
        private const val PREFIX = "appearance_"
        
        const val KEY_DREAM_BRIGHTNESS_PERCENT = "${PREFIX}brightness"
        const val KEY_DEFAULT_BRIGHTNESS_FALLBACK = "${PREFIX}brightness_fallback"
        const val KEY_PNG_COMPRESSION_QUALITY = "${PREFIX}png_quality"

        const val DEFAULT_DREAM_BRIGHTNESS_PERCENT = 10
        const val DEFAULT_BRIGHTNESS_FALLBACK = 128
        const val DEFAULT_PNG_COMPRESSION_QUALITY = 100

        fun getDreamBrightnessPercent(context: Context): Int = 
            getPrefs(context).getInt(KEY_DREAM_BRIGHTNESS_PERCENT, DEFAULT_DREAM_BRIGHTNESS_PERCENT)
        fun setDreamBrightnessPercent(context: Context, value: Int) {
            getPrefs(context).edit { putInt(KEY_DREAM_BRIGHTNESS_PERCENT, value) }
            notifyListeners(KEY_DREAM_BRIGHTNESS_PERCENT)
        }

        fun getDefaultBrightnessFallback(context: Context): Int = 
            getPrefs(context).getInt(KEY_DEFAULT_BRIGHTNESS_FALLBACK, DEFAULT_BRIGHTNESS_FALLBACK)
        fun setDefaultBrightnessFallback(context: Context, value: Int) {
            getPrefs(context).edit { putInt(KEY_DEFAULT_BRIGHTNESS_FALLBACK, value) }
            notifyListeners(KEY_DEFAULT_BRIGHTNESS_FALLBACK)
        }

        fun getPngCompressionQuality(context: Context): Int = 
            getPrefs(context).getInt(KEY_PNG_COMPRESSION_QUALITY, DEFAULT_PNG_COMPRESSION_QUALITY)
        fun setPngCompressionQuality(context: Context, value: Int) {
            getPrefs(context).edit { putInt(KEY_PNG_COMPRESSION_QUALITY, value) }
            notifyListeners(KEY_PNG_COMPRESSION_QUALITY)
        }
    }

    // ==================== IMAGE PROCESSING ====================

    object ImageProcessing {
        // Style toggles
        const val KEY_USE_INVERTED_SKETCH = "img_use_inverted"
        const val KEY_ENABLE_COLOR_OUTLINES = "img_color_outlines"
        const val KEY_ENABLE_PENCIL_SKETCH = "img_pencil_sketch"
        const val KEY_USE_HISTOGRAM_EQ = "img_histogram_eq"

        // Line drawing
        const val KEY_MIN_LINE_THICKNESS = "img_min_line_thickness"
        const val KEY_MAX_LINE_THICKNESS = "img_max_line_thickness"

        // Filter parameters
        const val KEY_KUWAHARA_KERNEL_SIZE = "img_kuwahara_kernel"
        const val KEY_ADAPTIVE_EPSILON_RATIO = "img_adaptive_epsilon"
        const val KEY_FACE_EPSILON_RATIO = "img_face_epsilon"
        const val KEY_MIN_CONTOUR_LENGTH_RATIO = "img_min_contour"
        const val KEY_HIERARCHY_MAX_LEVEL = "img_hierarchy_max"

        // Canny edge detection
        const val KEY_CANNY_MIN_LOWER = "img_canny_min_lower"
        const val KEY_CANNY_LOWER_RATIO = "img_canny_lower_ratio"
        const val KEY_CANNY_MAX_UPPER = "img_canny_max_upper"
        const val KEY_CANNY_UPPER_RATIO = "img_canny_upper_ratio"
        const val KEY_CANNY_APERTURE = "img_canny_aperture"

        // Adaptive threshold
        const val KEY_ADAPTIVE_BLOCK_SIZE = "img_adaptive_block"
        const val KEY_ADAPTIVE_C_CONSTANT = "img_adaptive_c"

        // Morphology
        const val KEY_MORPH_KERNEL_SIZE = "img_morph_kernel"
        const val KEY_DILATE_KERNEL_SIZE = "img_dilate_kernel"

        // Pencil sketch
        const val KEY_GAUSSIAN_BLUR_SIZE = "img_gaussian_blur"
        const val KEY_DIVIDE_SCALE = "img_divide_scale"

        // Defaults
        const val DEFAULT_USE_INVERTED_SKETCH = true
        const val DEFAULT_ENABLE_COLOR_OUTLINES = false
        const val DEFAULT_ENABLE_PENCIL_SKETCH = false
        const val DEFAULT_USE_HISTOGRAM_EQ = true
        const val DEFAULT_MIN_LINE_THICKNESS = 2
        const val DEFAULT_MAX_LINE_THICKNESS = 4
        const val DEFAULT_KUWAHARA_KERNEL_SIZE = 5
        const val DEFAULT_ADAPTIVE_EPSILON_RATIO = 0.01f
        const val DEFAULT_FACE_EPSILON_RATIO = 0.005f
        const val DEFAULT_MIN_CONTOUR_LENGTH_RATIO = 0.01f
        const val DEFAULT_HIERARCHY_MAX_LEVEL = 2
        const val DEFAULT_CANNY_MIN_LOWER = 40.0f
        const val DEFAULT_CANNY_LOWER_RATIO = 0.66f
        const val DEFAULT_CANNY_MAX_UPPER = 200.0f
        const val DEFAULT_CANNY_UPPER_RATIO = 1.33f
        const val DEFAULT_CANNY_APERTURE = 3
        const val DEFAULT_ADAPTIVE_BLOCK_SIZE = 11
        const val DEFAULT_ADAPTIVE_C_CONSTANT = 2.0f
        const val DEFAULT_MORPH_KERNEL_SIZE = 3.0f
        const val DEFAULT_DILATE_KERNEL_SIZE = 2.0f
        const val DEFAULT_GAUSSIAN_BLUR_SIZE = 25.0f
        const val DEFAULT_DIVIDE_SCALE = 256.0f

        // Style toggles
        fun getUseInvertedSketch(context: Context): Boolean = 
            getPrefs(context).getBoolean(KEY_USE_INVERTED_SKETCH, DEFAULT_USE_INVERTED_SKETCH)
        fun setUseInvertedSketch(context: Context, value: Boolean) {
            getPrefs(context).edit { putBoolean(KEY_USE_INVERTED_SKETCH, value) }
            notifyListeners(KEY_USE_INVERTED_SKETCH)
        }

        fun getEnableColorOutlines(context: Context): Boolean = 
            getPrefs(context).getBoolean(KEY_ENABLE_COLOR_OUTLINES, DEFAULT_ENABLE_COLOR_OUTLINES)
        fun setEnableColorOutlines(context: Context, value: Boolean) {
            getPrefs(context).edit { putBoolean(KEY_ENABLE_COLOR_OUTLINES, value) }
            notifyListeners(KEY_ENABLE_COLOR_OUTLINES)
        }

        fun getEnablePencilSketch(context: Context): Boolean = 
            getPrefs(context).getBoolean(KEY_ENABLE_PENCIL_SKETCH, DEFAULT_ENABLE_PENCIL_SKETCH)
        fun setEnablePencilSketch(context: Context, value: Boolean) {
            getPrefs(context).edit { putBoolean(KEY_ENABLE_PENCIL_SKETCH, value) }
            notifyListeners(KEY_ENABLE_PENCIL_SKETCH)
        }

        fun getUseHistogramEq(context: Context): Boolean = 
            getPrefs(context).getBoolean(KEY_USE_HISTOGRAM_EQ, DEFAULT_USE_HISTOGRAM_EQ)
        fun setUseHistogramEq(context: Context, value: Boolean) {
            getPrefs(context).edit { putBoolean(KEY_USE_HISTOGRAM_EQ, value) }
            notifyListeners(KEY_USE_HISTOGRAM_EQ)
        }

        // Line drawing
        fun getMinLineThickness(context: Context): Int = 
            getPrefs(context).getInt(KEY_MIN_LINE_THICKNESS, DEFAULT_MIN_LINE_THICKNESS)
        fun setMinLineThickness(context: Context, value: Int) {
            getPrefs(context).edit { putInt(KEY_MIN_LINE_THICKNESS, value) }
            notifyListeners(KEY_MIN_LINE_THICKNESS)
        }

        fun getMaxLineThickness(context: Context): Int = 
            getPrefs(context).getInt(KEY_MAX_LINE_THICKNESS, DEFAULT_MAX_LINE_THICKNESS)
        fun setMaxLineThickness(context: Context, value: Int) {
            getPrefs(context).edit { putInt(KEY_MAX_LINE_THICKNESS, value) }
            notifyListeners(KEY_MAX_LINE_THICKNESS)
        }

        // Filter parameters
        fun getKuwaharaKernelSize(context: Context): Int = 
            getPrefs(context).getInt(KEY_KUWAHARA_KERNEL_SIZE, DEFAULT_KUWAHARA_KERNEL_SIZE)
        fun setKuwaharaKernelSize(context: Context, value: Int) {
            getPrefs(context).edit { putInt(KEY_KUWAHARA_KERNEL_SIZE, value) }
            notifyListeners(KEY_KUWAHARA_KERNEL_SIZE)
        }

        fun getAdaptiveEpsilonRatio(context: Context): Float = 
            getPrefs(context).getFloat(KEY_ADAPTIVE_EPSILON_RATIO, DEFAULT_ADAPTIVE_EPSILON_RATIO)
        fun setAdaptiveEpsilonRatio(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_ADAPTIVE_EPSILON_RATIO, value) }
            notifyListeners(KEY_ADAPTIVE_EPSILON_RATIO)
        }

        fun getFaceEpsilonRatio(context: Context): Float = 
            getPrefs(context).getFloat(KEY_FACE_EPSILON_RATIO, DEFAULT_FACE_EPSILON_RATIO)
        fun setFaceEpsilonRatio(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_FACE_EPSILON_RATIO, value) }
            notifyListeners(KEY_FACE_EPSILON_RATIO)
        }

        fun getMinContourLengthRatio(context: Context): Float = 
            getPrefs(context).getFloat(KEY_MIN_CONTOUR_LENGTH_RATIO, DEFAULT_MIN_CONTOUR_LENGTH_RATIO)
        fun setMinContourLengthRatio(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_MIN_CONTOUR_LENGTH_RATIO, value) }
            notifyListeners(KEY_MIN_CONTOUR_LENGTH_RATIO)
        }

        fun getHierarchyMaxLevel(context: Context): Int = 
            getPrefs(context).getInt(KEY_HIERARCHY_MAX_LEVEL, DEFAULT_HIERARCHY_MAX_LEVEL)
        fun setHierarchyMaxLevel(context: Context, value: Int) {
            getPrefs(context).edit { putInt(KEY_HIERARCHY_MAX_LEVEL, value) }
            notifyListeners(KEY_HIERARCHY_MAX_LEVEL)
        }

        // Canny edge detection
        fun getCannyMinLower(context: Context): Float = 
            getPrefs(context).getFloat(KEY_CANNY_MIN_LOWER, DEFAULT_CANNY_MIN_LOWER)
        fun setCannyMinLower(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_CANNY_MIN_LOWER, value) }
            notifyListeners(KEY_CANNY_MIN_LOWER)
        }

        fun getCannyLowerRatio(context: Context): Float = 
            getPrefs(context).getFloat(KEY_CANNY_LOWER_RATIO, DEFAULT_CANNY_LOWER_RATIO)
        fun setCannyLowerRatio(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_CANNY_LOWER_RATIO, value) }
            notifyListeners(KEY_CANNY_LOWER_RATIO)
        }

        fun getCannyMaxUpper(context: Context): Float = 
            getPrefs(context).getFloat(KEY_CANNY_MAX_UPPER, DEFAULT_CANNY_MAX_UPPER)
        fun setCannyMaxUpper(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_CANNY_MAX_UPPER, value) }
            notifyListeners(KEY_CANNY_MAX_UPPER)
        }

        fun getCannyUpperRatio(context: Context): Float = 
            getPrefs(context).getFloat(KEY_CANNY_UPPER_RATIO, DEFAULT_CANNY_UPPER_RATIO)
        fun setCannyUpperRatio(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_CANNY_UPPER_RATIO, value) }
            notifyListeners(KEY_CANNY_UPPER_RATIO)
        }

        fun getCannyAperture(context: Context): Int = 
            getPrefs(context).getInt(KEY_CANNY_APERTURE, DEFAULT_CANNY_APERTURE)
        fun setCannyAperture(context: Context, value: Int) {
            getPrefs(context).edit { putInt(KEY_CANNY_APERTURE, value) }
            notifyListeners(KEY_CANNY_APERTURE)
        }

        // Adaptive threshold
        fun getAdaptiveBlockSize(context: Context): Int = 
            getPrefs(context).getInt(KEY_ADAPTIVE_BLOCK_SIZE, DEFAULT_ADAPTIVE_BLOCK_SIZE)
        fun setAdaptiveBlockSize(context: Context, value: Int) {
            getPrefs(context).edit { putInt(KEY_ADAPTIVE_BLOCK_SIZE, value) }
            notifyListeners(KEY_ADAPTIVE_BLOCK_SIZE)
        }

        fun getAdaptiveCConstant(context: Context): Float = 
            getPrefs(context).getFloat(KEY_ADAPTIVE_C_CONSTANT, DEFAULT_ADAPTIVE_C_CONSTANT)
        fun setAdaptiveCConstant(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_ADAPTIVE_C_CONSTANT, value) }
            notifyListeners(KEY_ADAPTIVE_C_CONSTANT)
        }

        // Morphology
        fun getMorphKernelSize(context: Context): Float = 
            getPrefs(context).getFloat(KEY_MORPH_KERNEL_SIZE, DEFAULT_MORPH_KERNEL_SIZE)
        fun setMorphKernelSize(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_MORPH_KERNEL_SIZE, value) }
            notifyListeners(KEY_MORPH_KERNEL_SIZE)
        }

        fun getDilateKernelSize(context: Context): Float = 
            getPrefs(context).getFloat(KEY_DILATE_KERNEL_SIZE, DEFAULT_DILATE_KERNEL_SIZE)
        fun setDilateKernelSize(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_DILATE_KERNEL_SIZE, value) }
            notifyListeners(KEY_DILATE_KERNEL_SIZE)
        }

        // Pencil sketch
        fun getGaussianBlurSize(context: Context): Float = 
            getPrefs(context).getFloat(KEY_GAUSSIAN_BLUR_SIZE, DEFAULT_GAUSSIAN_BLUR_SIZE)
        fun setGaussianBlurSize(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_GAUSSIAN_BLUR_SIZE, value) }
            notifyListeners(KEY_GAUSSIAN_BLUR_SIZE)
        }

        fun getDivideScale(context: Context): Float = 
            getPrefs(context).getFloat(KEY_DIVIDE_SCALE, DEFAULT_DIVIDE_SCALE)
        fun setDivideScale(context: Context, value: Float) {
            getPrefs(context).edit { putFloat(KEY_DIVIDE_SCALE, value) }
            notifyListeners(KEY_DIVIDE_SCALE)
        }
    }

    // ==================== UTILITY ====================

    /**
     * Reset all settings to defaults.
     */
    fun resetAllToDefaults(context: Context) {
        getPrefs(context).edit { clear() }
        notifyListeners("*")
    }
}
