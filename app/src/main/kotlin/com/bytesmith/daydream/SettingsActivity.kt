package com.bytesmith.daydream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.*
// import org.opencv.android.OpenCVLoader  // COMMENTED OUT - Using GPUImage instead
import java.text.SimpleDateFormat
import java.util.*

/**
 * Settings activity with live preview for all Daydream customization options.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var clockPreview: LinearLayout
    private lateinit var imagePreview: ImageView
    private lateinit var previewLoading: ProgressBar
    private lateinit var previewTimeText: TextView
    private lateinit var previewDateText: TextView

    private val settingsScope = CoroutineScope(Dispatchers.Main + Job())
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null

    private var currentPreviewMode = PreviewMode.CLOCK
    private var sampleBitmap: Bitmap? = null

    enum class PreviewMode {
        CLOCK,
        IMAGE
    }

    companion object {
        private const val TAG = "SettingsActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // OPENCV INITIALIZATION COMMENTED OUT - Using GPUImage instead
        // if (!OpenCVLoader.initLocal()) {
        //     Log.e(TAG, "OpenCV failed to initialize. Image preview will be disabled.")
        // }
        
        setContentView(R.layout.activity_settings)

        setupToolbar()
        setupPreview()
        setupSettingsListener()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupPreview() {
        clockPreview = findViewById(R.id.clockPreview)
        imagePreview = findViewById(R.id.imagePreview)
        previewLoading = findViewById(R.id.previewLoading)
        previewTimeText = findViewById(R.id.previewTimeText)
        previewDateText = findViewById(R.id.previewDateText)

        // Set initial time preview
        updateClockPreview()
    }

    private fun setupSettingsListener() {
        DaydreamSettings.addListener { key ->
            // Debounce updates to prevent lag during rapid slider changes
            debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
            debounceRunnable = Runnable {
                onSettingChanged(key)
            }
            debounceHandler.postDelayed(debounceRunnable!!, 300)
        }
    }

    private fun onSettingChanged(key: String) {
        when {
            // Image processing settings - show image preview
            key.startsWith("img_") -> {
                showImagePreview()
                regenerateImagePreview()
            }
            // Animation settings - animate the clock preview
            key.startsWith("anim_") -> {
                showClockPreview()
                animateClockPreview()
            }
            // Other settings - just update clock preview
            else -> {
                showClockPreview()
                updateClockPreview()
            }
        }
    }

    private fun showClockPreview() {
        if (currentPreviewMode != PreviewMode.CLOCK) {
            currentPreviewMode = PreviewMode.CLOCK
            clockPreview.visibility = View.VISIBLE
            imagePreview.visibility = View.GONE
            previewLoading.visibility = View.GONE
        }
    }

    private fun showImagePreview() {
        if (currentPreviewMode != PreviewMode.IMAGE) {
            currentPreviewMode = PreviewMode.IMAGE
            clockPreview.visibility = View.GONE
            imagePreview.visibility = View.VISIBLE
        }
    }

    private fun updateClockPreview() {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
        previewDateText.text = dateFormat.format(calendar.time)

        // Simple time to words for preview
        val hour = calendar.get(Calendar.HOUR)
        val minute = calendar.get(Calendar.MINUTE)
        val timeWords = getSimpleTimeWords(if (hour == 0) 12 else hour, minute)
        previewTimeText.text = timeWords
    }

    private fun animateClockPreview() {
        val fadeOutMs = DaydreamSettings.Animation.getTransitionOutMs(this)
        val fadeInMs = DaydreamSettings.Animation.getTransitionInMs(this)

        clockPreview.animate()
            .alpha(0f)
            .setDuration(fadeOutMs)
            .withEndAction {
                updateClockPreview()
                clockPreview.animate()
                    .alpha(1f)
                    .setDuration(fadeInMs)
                    .start()
            }
            .start()
    }

    private fun regenerateImagePreview() {
        previewLoading.visibility = View.VISIBLE

        settingsScope.launch {
            val sourceBitmap = getSourceBitmap()
            if (sourceBitmap != null) {
                val processedBitmap = withContext(Dispatchers.IO) {
                    ArtisticRenderer.create(this@SettingsActivity, sourceBitmap)
                }
                previewLoading.visibility = View.GONE
                processedBitmap?.let {
                    imagePreview.setImageBitmap(it)
                }
            } else {
                previewLoading.visibility = View.GONE
            }
        }
    }

    private fun getSourceBitmap(): Bitmap? {
        // First try to use last known album art
        NotificationService.lastKnownMediaInfo?.albumArt?.let { return it }

        // Otherwise create a fallback bitmap for preview
        if (sampleBitmap == null) {
            sampleBitmap = createFallbackBitmap()
        }
        return sampleBitmap
    }

    private fun createFallbackBitmap(): Bitmap {
        val width = 400
        val height = 400
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()

        // Create a simple gradient
        val gradient = android.graphics.LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xFF1a1a2e.toInt(), 0xFF16213e.toInt(), 0xFF0f3460.toInt()),
            null,
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = gradient
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Add some circles for visual interest
        paint.shader = null
        paint.color = 0x33FFFFFF
        canvas.drawCircle(width * 0.3f, height * 0.4f, 80f, paint)
        canvas.drawCircle(width * 0.7f, height * 0.6f, 60f, paint)

        return bitmap
    }

    private fun getSimpleTimeWords(hour: Int, minute: Int): String {
        val hourWords = listOf(
            "Twelve", "One", "Two", "Three", "Four", "Five",
            "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve"
        )

        return when {
            minute == 0 -> "${hourWords[hour]}\nO'Clock"
            minute == 15 -> "Quarter\nPast\n${hourWords[hour]}"
            minute == 30 -> "Half\nPast\n${hourWords[hour]}"
            minute == 45 -> "Quarter\nTo\n${hourWords[(hour % 12) + 1]}"
            minute < 30 -> "Twenty\n${if (minute > 20) (minute - 20).toString() else ""}\nPast\n${hourWords[hour]}"
            else -> "Twenty\n${if (minute < 40) "" else (60 - minute).toString()}\nTo\n${hourWords[(hour % 12) + 1]}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        settingsScope.cancel()
        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        sampleBitmap?.recycle()
    }
}
