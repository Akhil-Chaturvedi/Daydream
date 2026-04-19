package com.bytesmith.daydream

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceCategory

/**
 * Settings fragment containing all Daydream customization options
 * organized into logical categories.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_root, rootKey)
        setupPreferenceListeners()
    }

    private fun setupPreferenceListeners() {
        // Animation settings
        findPreference<SeekBarPreference>(DaydreamSettings.Animation.KEY_TRANSITION_OUT_MS)?.apply {
            min = 100
            max = 3000
            setOnPreferenceChangeListener { _, newValue ->
                DaydreamSettings.Animation.setTransitionOutMs(requireContext(), (newValue as Int).toLong())
                true
            }
        }

        findPreference<SeekBarPreference>(DaydreamSettings.Animation.KEY_TRANSITION_IN_MS)?.apply {
            min = 100
            max = 3000
            setOnPreferenceChangeListener { _, newValue ->
                DaydreamSettings.Animation.setTransitionInMs(requireContext(), (newValue as Int).toLong())
                true
            }
        }

        findPreference<SeekBarPreference>(DaydreamSettings.Animation.KEY_SCALE_ANIM_DURATION_MS)?.apply {
            min = 50
            max = 1000
            setOnPreferenceChangeListener { _, newValue ->
                DaydreamSettings.Animation.setScaleAnimDurationMs(requireContext(), (newValue as Int).toLong())
                true
            }
        }

        // Layout settings
        findPreference<SeekBarPreference>(DaydreamSettings.Layout.KEY_CONTENT_SCALE_PERCENT)?.apply {
            min = 0  // 0 = auto
            max = 100
            setOnPreferenceChangeListener { _, newValue ->
                val value = newValue as Int
                summary = if (value <= 0) "Auto (adapts to screen)" else "$value%"
                DaydreamSettings.Layout.setContentScalePercent(requireContext(), value)
                true
            }
            // Set initial summary
            val current = DaydreamSettings.Layout.getContentScalePercent(requireContext())
            summary = if (current <= 0) "Auto (adapts to screen)" else "$current%"
        }

        // Appearance settings
        findPreference<SeekBarPreference>(DaydreamSettings.Appearance.KEY_DREAM_BRIGHTNESS_PERCENT)?.apply {
            min = 1
            max = 100
            setOnPreferenceChangeListener { _, newValue ->
                DaydreamSettings.Appearance.setDreamBrightnessPercent(requireContext(), newValue as Int)
                true
            }
        }

        // Gesture settings
        findPreference<SeekBarPreference>(DaydreamSettings.Gestures.KEY_SWIPE_THRESHOLD)?.apply {
            min = 20
            max = 300
            setOnPreferenceChangeListener { _, newValue ->
                DaydreamSettings.Gestures.setSwipeThreshold(requireContext(), newValue as Int)
                true
            }
        }

        // Timing settings
        findPreference<SeekBarPreference>(DaydreamSettings.Timing.KEY_BATTERY_UPDATE_INTERVAL_MS)?.apply {
            min = 10000  // 10 seconds
            max = 300000 // 5 minutes
            setOnPreferenceChangeListener { _, newValue ->
                val ms = newValue as Int
                summary = formatDuration(ms.toLong())
                DaydreamSettings.Timing.setBatteryUpdateIntervalMs(requireContext(), ms.toLong())
                true
            }
            summary = formatDuration(DaydreamSettings.Timing.getBatteryUpdateIntervalMs(requireContext()))
        }

        findPreference<SeekBarPreference>(DaydreamSettings.Timing.KEY_MEDIA_SESSION_CHECK_INTERVAL_MS)?.apply {
            min = 1000   // 1 second
            max = 30000  // 30 seconds
            setOnPreferenceChangeListener { _, newValue ->
                val ms = newValue as Int
                summary = formatDuration(ms.toLong())
                DaydreamSettings.Timing.setMediaSessionCheckIntervalMs(requireContext(), ms.toLong())
                true
            }
            summary = formatDuration(DaydreamSettings.Timing.getMediaSessionCheckIntervalMs(requireContext()))
        }

        // Image processing toggles
        findPreference<SwitchPreferenceCompat>(DaydreamSettings.ImageProcessing.KEY_USE_INVERTED_SKETCH)?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                DaydreamSettings.ImageProcessing.setUseInvertedSketch(requireContext(), newValue as Boolean)
                true
            }
        }

        findPreference<SwitchPreferenceCompat>(DaydreamSettings.ImageProcessing.KEY_ENABLE_PENCIL_SKETCH)?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                DaydreamSettings.ImageProcessing.setEnablePencilSketch(requireContext(), newValue as Boolean)
                true
            }
        }

        findPreference<SwitchPreferenceCompat>(DaydreamSettings.ImageProcessing.KEY_USE_HISTOGRAM_EQ)?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                DaydreamSettings.ImageProcessing.setUseHistogramEq(requireContext(), newValue as Boolean)
                true
            }
        }

        // Image processing sliders
        findPreference<SeekBarPreference>(DaydreamSettings.ImageProcessing.KEY_MIN_LINE_THICKNESS)?.apply {
            min = 1
            max = 10
            setOnPreferenceChangeListener { _, newValue ->
                DaydreamSettings.ImageProcessing.setMinLineThickness(requireContext(), newValue as Int)
                true
            }
        }

        findPreference<SeekBarPreference>(DaydreamSettings.ImageProcessing.KEY_MAX_LINE_THICKNESS)?.apply {
            min = 1
            max = 15
            setOnPreferenceChangeListener { _, newValue ->
                DaydreamSettings.ImageProcessing.setMaxLineThickness(requireContext(), newValue as Int)
                true
            }
        }

        findPreference<SeekBarPreference>(DaydreamSettings.ImageProcessing.KEY_KUWAHARA_KERNEL_SIZE)?.apply {
            min = 3
            max = 15
            setOnPreferenceChangeListener { _, newValue ->
                // Must be odd number
                var value = newValue as Int
                if (value % 2 == 0) value++
                DaydreamSettings.ImageProcessing.setKuwaharaKernelSize(requireContext(), value)
                true
            }
        }

        findPreference<SeekBarPreference>(DaydreamSettings.ImageProcessing.KEY_HIERARCHY_MAX_LEVEL)?.apply {
            min = 1
            max = 5
            setOnPreferenceChangeListener { _, newValue ->
                DaydreamSettings.ImageProcessing.setHierarchyMaxLevel(requireContext(), newValue as Int)
                true
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        return when {
            ms >= 60000 -> "${ms / 60000} min"
            ms >= 1000 -> "${ms / 1000} sec"
            else -> "$ms ms"
        }
    }
}
