package com.bytesmith.daydream

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.text.Spanned
import android.text.TextUtils
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.collection.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bytesmith.daydream.databinding.ActivityScreensaverBinding
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

class DreamService : android.service.dreams.DreamService() {

    private lateinit var binding: ActivityScreensaverBinding
    private val dreamServiceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var audioManager: AudioManager
    private lateinit var songGestureDetector: GestureDetector
    private var canvasArtCacheDir: File? = null
    private var fixedHorizontalMargin = 0
    private var iconCacheDir: File? = null
    private var maxVerticalMargin = 0
    private var isFirstLayoutComplete = false
    private var isLandscape = false
    private var lastScreenWidth = 0
    private var lastScreenHeight = 0
    private val iconCache = mutableMapOf<String, Drawable>()
    private val ICON_STYLE_SYSTEM = 0
    private val ICON_STYLE_MONOCHROME = 1
    private val ICON_STYLE_OFF = 2
    private var currentIconStyle = ICON_STYLE_SYSTEM
    private val memoryCache: LruCache<String, Bitmap>

    init {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
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

    private val layoutChangeListener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        val newWidth = right - left
        val newHeight = bottom - top
        if (newWidth != lastScreenWidth || newHeight != lastScreenHeight) {
            Log.d(TAG, "Screen dimensions changed. Recalculating bounds...")
            lastScreenWidth = newWidth
            lastScreenHeight = newHeight
            updateBoundsAndLayout()
            restoreUiState()  // Added this to restore UI after bounds update
        }
    }

    companion object {
        private const val TAG = "DreamService"
        private const val UPDATE_INTERVAL = 1000L
        private const val BATTERY_UPDATE_INTERVAL = 60000L
        private const val SHIFT_DURATION = 10000L
        private var instanceRef: WeakReference<DreamService>? = null
        private var songCount = 0
        private var lastValidSongInfoHtml: Spanned? = null
        private val TIME_WORDS_FORMAT = SimpleDateFormat("hh_mm_a", Locale.getDefault())
        private val DAY_DATE_FORMAT = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

        init {
            try {
                System.loadLibrary("opencv_java4")
                Log.d(TAG, "OpenCV native library loaded successfully via System.loadLibrary.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "!!! FATAL: OpenCV native library failed to load !!!", e)
            }
        }

        @JvmStatic
        fun updateSongInfo(songName: String?) {
            val instance = instanceRef?.get() ?: return
            if (instance.window == null || !instance::binding.isInitialized) {
                Log.e(TAG, "DreamService instance is not ready in updateSongInfo")
                return
            }
            val textView = instance.binding.songName
            if (!songName.isNullOrBlank()) {
                val formattedText = if (songName.contains('\n')) {
                    val (title, artist) = songName.split('\n', limit = 2)
                    val htmlString = "<b><font size='+4'>${escapeHtml(capitalizeWords(title))}</font></b><br>${escapeHtml(capitalizeWords(artist))}"
                    fromHtml(htmlString)
                } else {
                    val htmlString = "<b><font size='+4'>${escapeHtml(capitalizeWords(songName))}</font></b>"
                    fromHtml(htmlString)
                }
                textView.text = formattedText
                textView.visibility = View.VISIBLE
                textView.gravity = android.view.Gravity.CENTER_HORIZONTAL
                lastValidSongInfoHtml = formattedText
            } else {
                if (lastValidSongInfoHtml != null) {
                    textView.text = lastValidSongInfoHtml
                    textView.visibility = View.VISIBLE
                } else {
                    textView.visibility = View.GONE
                }
            }
        }

        @JvmStatic
        fun updateSongCount(count: Int) {
            songCount = count
            instanceRef?.get()?.updateSongCountUI(count)
        }

        private fun escapeHtml(text: String?): String {
            return if (text == null) "" else TextUtils.htmlEncode(text)
        }

        private fun capitalizeWords(str: String?): String {
            if (str.isNullOrEmpty()) return ""
            return str.split(Regex("\\s+")).joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }
        }

        fun fromHtml(html: String): Spanned {
            return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fixedHorizontalMargin = resources.getDimensionPixelSize(R.dimen.fixed_horizontal_margin)
        instanceRef = WeakReference(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        canvasArtCacheDir = File(cacheDir, "canvas_art_cache").also { it.mkdirs() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NotificationService.setDreamActive(true)
        instanceRef = WeakReference(this)
        registerReceiver(updateNotificationReceiver, IntentFilter(NotificationService.UPDATE_NOTIFICATIONS_ACTION))
        registerReceiver(mediaMetadataReceiver, IntentFilter(NotificationService.MEDIA_METADATA_UPDATED_ACTION))

        setupUi()
        populateUi()
        startPeriodicUpdates()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed. Re-populating existing UI with current data.")
        // Removed the post to restoreUiState() here, as it will be handled in layoutChangeListener
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUi() {
        isInteractive = true
        isFullscreen = true

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        binding = ActivityScreensaverBinding.inflate(inflater)
        setContentView(binding.root)

        adjustBrightness(10)
        setSystemUiVisibility()

        binding.songName.apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            songGestureDetector = GestureDetector(this@DreamService, SongGestureListener())
            setOnTouchListener { _, event -> songGestureDetector.onTouchEvent(event) }
        }
        val exitGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                finish()
                return true
            }
            override fun onDown(e: MotionEvent): Boolean {
                return !isPointInsideView(e.rawX, e.rawY, binding.songName)
            }
        })
        window.decorView.setOnTouchListener { _, event ->
            exitGestureDetector.onTouchEvent(event) || !isPointInsideView(event.rawX, event.rawY, binding.songName)
        }

        window.decorView.removeOnLayoutChangeListener(layoutChangeListener)
        window.decorView.addOnLayoutChangeListener(layoutChangeListener)
    }

    private fun populateUi() {
        Log.d(TAG, "Populating UI with all current data.")
        val prefs = getSharedPreferences("DaydreamSettings", Context.MODE_PRIVATE)
        currentIconStyle = prefs.getInt("notificationIconStyle", ICON_STYLE_SYSTEM)

        // Explicitly refresh all data sources and UI components
        updateBatteryInfo()
        updateTimeInWords()
        updateBatteryIconSize()
        updateDayDateTextView()
        updateSongCountUI(songCount)

        // Explicitly refresh notification icons
        updateNotificationIcons(NotificationService.getPackagesForIconDisplay())

        // Explicitly refresh media display with proper state checking
        NotificationService.lastKnownMediaInfo?.let { info ->
            updateMediaDisplay(info.cleanedSongString, info.albumArt, info.cacheKey)
        } ?: run {
            // If no media info, ensure song name is hidden
            binding.songName.visibility = View.GONE
            binding.canvasArtImageview.setImageBitmap(null)
        }

        // Force layout update
        binding.root.requestLayout()
    }

    private fun updateMediaDisplay(songString: String?, albumArt: Bitmap?, cacheKey: String?) {
        Log.d(TAG, "Updating media display for cache key: $cacheKey")
        // Safeguard: Only update if binding is initialized
        if (::binding.isInitialized) {
            updateSongInfo(songString)
            if (albumArt != null && cacheKey != null) {
                processAndDisplayCanvasArt(albumArt, cacheKey)
                // Ensure canvas art visibility matches orientation
                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                binding.canvasArtImageview.visibility = if (isLandscape) View.VISIBLE else View.GONE
            } else {
                binding.canvasArtImageview.setImageBitmap(null)
                binding.canvasArtImageview.visibility = View.GONE
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        NotificationService.setDreamActive(false)
        dreamServiceScope.cancel()
        restoreBrightness()
        window.decorView.removeOnLayoutChangeListener(layoutChangeListener)
        try {
            unregisterReceiver(updateNotificationReceiver)
            unregisterReceiver(mediaMetadataReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receivers not registered, skipping unregister.")
        }
        window.decorView.setOnTouchListener(null)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        Log.d(TAG, "Dreaming started, ensuring UI state is restored")

        // Small delay to ensure everything is initialized
        binding.root.postDelayed({
            restoreUiState()
        }, 100)
    }

    private val mediaMetadataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bundle = intent.extras
            val cleanedSongString = bundle?.getString("cleanedSongString")
            val albumArt: Bitmap? = bundle?.let { getParcelableCompat(it, "albumArt", Bitmap::class.java) }
            val cacheKey: String? = bundle?.getString("cacheKey")
            updateMediaDisplay(cleanedSongString, albumArt, cacheKey)
        }
    }

    private fun updateBoundsAndLayout() {
        val rootView = window.decorView
        val screenHeight = rootView.height
        val contentHeight = binding.contentContainer.height
        if (contentHeight > 0) {
            maxVerticalMargin = (screenHeight - contentHeight).coerceAtLeast(0)
            Log.d(TAG, "Vertical bounds recalculated. Max margin: $maxVerticalMargin")
        }
        val currentOrientation = resources.configuration.orientation
        isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        val songParams = binding.songName.layoutParams as RelativeLayout.LayoutParams
        if (isLandscape) {
            binding.canvasArtImageview.visibility = View.VISIBLE
            songParams.removeRule(RelativeLayout.CENTER_HORIZONTAL)
            songParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            songParams.addRule(RelativeLayout.RIGHT_OF, R.id.center_anchor)
            songParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
        } else {
            binding.canvasArtImageview.visibility = View.GONE
            songParams.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            songParams.removeRule(RelativeLayout.RIGHT_OF)
            songParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            songParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
        }
        binding.songName.layoutParams = songParams
        if (!isFirstLayoutComplete) {
            isFirstLayoutComplete = true
            Log.d(TAG, "First layout complete. Setting initial position.")
            moveToRandomPosition()
        }

        // Force refresh UI state after layout changes
        restoreUiState()
    }

    private fun restoreUiState() {
        Log.d(TAG, "Restoring UI state after layout/orientation change")
        // Restore media display if available
        NotificationService.lastKnownMediaInfo?.let { info ->
            updateMediaDisplay(info.cleanedSongString, info.albumArt, info.cacheKey)
        }
        // Restore song count
        updateSongCountUI(songCount)
        // Restore notification icons
        updateNotificationIcons(NotificationService.getPackagesForIconDisplay())
        // Force visibility updates
        binding.songName.post {
            if (binding.songName.text.isNotEmpty()) {
                binding.songName.visibility = View.VISIBLE
            }
        }
        binding.root.requestLayout()  // Added to force redraw after updates
    }

    private inner class SongGestureListener : GestureDetector.SimpleOnGestureListener() {
        private val swipeThreshold = 100
        private val swipeVelocityThreshold = 100
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            e1 ?: return false
            val diffX = e2.x - e1.x
            if (Math.abs(diffX) > Math.abs(e2.y - e1.y) &&
                Math.abs(diffX) > swipeThreshold &&
                Math.abs(velocityX) > swipeVelocityThreshold
            ) {
                if (diffX > 0) {
                    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                } else {
                    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
                }
                return true
            }
            return false
        }
    }

    private fun startPeriodicUpdates() {
        dreamServiceScope.coroutineContext.cancelChildren()

        dreamServiceScope.launch {
            while (isActive) {
                updateTimeInWords()
                updateDayDateTextView()
                delay(UPDATE_INTERVAL)
            }
        }
        dreamServiceScope.launch {
            while (isActive) {
                updateBatteryInfo()
                delay(BATTERY_UPDATE_INTERVAL)
            }
        }
        dreamServiceScope.launch {
            while (isActive) {
                moveToRandomPosition()
                delay(SHIFT_DURATION)
            }
        }
    }

    private fun moveToRandomPosition() {
        if (!isFirstLayoutComplete) {
            Log.w(TAG, "Layout not ready, skipping shift.")
            return
        }
        val params = binding.contentContainer.layoutParams as RelativeLayout.LayoutParams
        params.leftMargin = fixedHorizontalMargin
        params.topMargin = if (maxVerticalMargin > 0) Random.nextInt(0, maxVerticalMargin) else 0
        binding.contentContainer.layoutParams = params
        Log.d(TAG, "Shifting content to margins: (${params.leftMargin}, ${params.topMargin})")
    }

    private fun adjustBrightness(brightnessLevel: Int) {
        BrightnessService.changeBrightness(this, brightnessLevel)
    }

    private fun restoreBrightness() {
        Log.d(TAG, "Attempting to restore original brightness")
        BrightnessService.restoreOriginalBrightness(this)
    }

    private fun updateTimeInWords() {
        binding.timeInWords.text = fromHtml(getTimeInWords())
    }

    private fun getTimeInWords(): String {
        val calendar = Calendar.getInstance()
        val timeKey = "time_${TIME_WORDS_FORMAT.format(calendar.time).replace(":", "_").uppercase()}"
        val resId = resources.getIdentifier(timeKey, "string", packageName)
        return if (resId == 0) getString(R.string.default_time_string) else getString(resId)
    }

    private fun updateDayDateTextView() {
        binding.dayDate.text = getDayDate()
    }

    private fun getDayDate(): String {
        return DAY_DATE_FORMAT.format(Calendar.getInstance().time)
    }

    private fun updateBatteryInfo() {
        val batteryLevel = getBatteryLevel()
        binding.batteryInfo.text = "$batteryLevel%"
        binding.batteryIcon.setImageResource(getBatteryIconResId(batteryLevel))
    }

    private fun getBatteryLevel(): Int {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return batteryStatus?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level == -1 || scale == -1) 0 else (level.toFloat() / scale.toFloat() * 100).toInt()
        } ?: 0
    }

    private fun getBatteryIconResId(batteryLevel: Int): Int {
        return when {
            batteryLevel >= 100 -> R.drawable.battery_charging_100
            batteryLevel >= 95 -> R.drawable.battery_charging_95
            batteryLevel >= 90 -> R.drawable.battery_charging_90
            batteryLevel >= 85 -> R.drawable.battery_charging_85
            batteryLevel >= 80 -> R.drawable.battery_charging_80
            batteryLevel >= 75 -> R.drawable.battery_charging_75
            batteryLevel >= 70 -> R.drawable.battery_charging_70
            batteryLevel >= 65 -> R.drawable.battery_charging_65
            batteryLevel >= 60 -> R.drawable.battery_charging_60
            batteryLevel >= 55 -> R.drawable.battery_charging_55
            batteryLevel >= 50 -> R.drawable.battery_charging_50
            batteryLevel >= 45 -> R.drawable.battery_charging_45
            batteryLevel >= 40 -> R.drawable.battery_charging_40
            batteryLevel >= 35 -> R.drawable.battery_charging_35
            batteryLevel >= 30 -> R.drawable.battery_charging_30
            batteryLevel >= 25 -> R.drawable.battery_charging_25
            batteryLevel >= 20 -> R.drawable.battery_charging_20
            batteryLevel >= 15 -> R.drawable.battery_charging_15
            batteryLevel >= 10 -> R.drawable.battery_charging_10
            batteryLevel >= 5 -> R.drawable.battery_charging_5
            else -> R.drawable.battery_charging_1
        }
    }

    private fun updateNotificationIcons(notificationPackages: Set<String>?) {
        if (!::binding.isInitialized) return // Guard against uninitialized UI
        val container = binding.notificationIconContainer
        if (currentIconStyle == ICON_STYLE_OFF) {
            container.removeAllViews()
            return
        }
        container.removeAllViews()
        notificationPackages?.forEach { packageName ->
            fetchAndCacheNotificationIcon(packageName)?.let { icon ->
                val iconView = ImageView(this@DreamService).apply {
                    setImageDrawable(icon)
                    layoutParams = LinearLayout.LayoutParams(
                        resources.getDimensionPixelSize(R.dimen.notification_icon_size),
                        resources.getDimensionPixelSize(R.dimen.notification_icon_size)
                    ).apply {
                        marginEnd = resources.getDimensionPixelSize(R.dimen.notification_icon_margin)
                    }
                }
                container.addView(iconView)
            } ?: Log.w(TAG, "Could not fetch icon for package: $packageName")
        }
    }

    private fun fetchAndCacheNotificationIcon(packageName: String): Drawable? {
        val cacheKey = "$packageName-$currentIconStyle"
        if (iconCache.containsKey(cacheKey)) {
            return iconCache[cacheKey]
        }
        try {
            val finalDrawable: Drawable? = when (currentIconStyle) {
                ICON_STYLE_SYSTEM -> {
                    val systemIcon = NotificationService.getSmallIconForPackage(this, packageName)
                    systemIcon?.mutate()?.apply { setTint(Color.WHITE) }
                }
                ICON_STYLE_MONOCHROME -> {
                    val originalIcon = packageManager.getApplicationIcon(packageName)
                    val originalBitmap = originalIcon.toBitmap()
                    val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = Canvas(resultBitmap)
                    val paint = Paint().apply {
                        colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                    }
                    canvas.drawBitmap(originalBitmap, 0f, 0f, paint)
                    BitmapDrawable(resources, resultBitmap)
                }
                else -> {
                    return null
                }
            }
            finalDrawable?.let {
                iconCache[cacheKey] = it
                cacheIcon(packageName, it)
            }
            return finalDrawable
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Failed to get icon for package: $packageName", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process icon for $packageName", e)
            return null
        }
    }

    private fun cacheIcon(packageName: String, icon: Drawable) {
        if (iconCacheDir == null) {
            iconCacheDir = File(cacheDir, "icon_cache").also {
                if (!it.exists()) it.mkdirs()
            }
        }
        val fileName = "$packageName-$currentIconStyle.png"
        val iconFile = File(iconCacheDir, fileName)
        if (iconFile.exists() || icon !is BitmapDrawable) return
        try {
            FileOutputStream(iconFile).use { fos ->
                icon.bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to cache icon for package: $packageName, style: $currentIconStyle", e)
        }
    }

    private val updateNotificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NotificationService.UPDATE_NOTIFICATIONS_ACTION) {
                updateNotificationIcons(NotificationService.getPackagesForIconDisplay())
            }
        }
    }

    private fun processAndDisplayCanvasArt(sourceBitmap: Bitmap, cacheKey: String) {
        val cachedBitmap = memoryCache.get(cacheKey)
        if (cachedBitmap != null) {
            Log.d(TAG, "Canvas art loaded from memory cache!")
            binding.canvasArtImageview.setImageBitmap(cachedBitmap)
            return
        }
        dreamServiceScope.launch {
            val finalBitmap = generateAndCacheArt(sourceBitmap, cacheKey)
            finalBitmap?.let {
                memoryCache.put(cacheKey, it)
                Log.d(TAG, "UI: Setting final bitmap from background task.")
                binding.canvasArtImageview.setImageBitmap(it)
            }
        }
    }

    private suspend fun generateAndCacheArt(sourceBitmap: Bitmap, cacheKey: String): Bitmap? = withContext(Dispatchers.IO) {
        val cachedFile = File(canvasArtCacheDir, "$cacheKey.png")
        if (cachedFile.exists()) {
            Log.d(TAG, "Loading canvas art from disk cache for key: $cacheKey")
            return@withContext BitmapFactory.decodeFile(cachedFile.absolutePath)
        } else {
            Log.d(TAG, "Generating new canvas art for key: $cacheKey")
            val generatedArt = generateCanvasArt(sourceBitmap)
            generatedArt?.let {
                try {
                    FileOutputStream(cachedFile).use { out ->
                        it.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to save canvas art to cache", e)
                }
            }
            return@withContext generatedArt
        }
    }

    private fun generateCanvasArt(source: Bitmap): Bitmap? {
        return CanvasGenerator.create(this, source)
    }

    private fun updateSongCountUI(count: Int) {
        if (!::binding.isInitialized) return
        binding.songCount.apply {
            if (count > 0) {
                text = "Songs: $count"
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setSystemUiVisibility() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun startNotificationService() {
        startService(Intent(this, NotificationService::class.java))
    }

    private fun stopNotificationService() {
        stopService(Intent(this, NotificationService::class.java))
    }

    private fun updateBatteryIconSize() {
        binding.batteryIcon.layoutParams.apply {
            width = resources.getDimensionPixelSize(R.dimen.battery_icon_width)
            height = resources.getDimensionPixelSize(R.dimen.battery_icon_height)
        }
        binding.batteryIcon.requestLayout()
    }

    private fun dispatchMediaKeyEvent(keyCode: Int) {
        val eventTime = android.os.SystemClock.uptimeMillis()
        val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
        Log.d(TAG, "Dispatched media key event: ${KeyEvent.keyCodeToString(keyCode)}")
        sendBroadcast(Intent(NotificationService.MEDIA_INFO_REQUEST_ACTION))
        Log.d(TAG, "Sent immediate media info request after key dispatch.")
    }

    private fun isPointInsideView(x: Float, y: Float, view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val viewX = location[0]
        val viewY = location[1]
        return (x > viewX && x < (viewX + view.width)) && (y > viewY && y < (viewY + view.height))
    }
}