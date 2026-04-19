package com.bytesmith.daydream

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
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
import android.os.Bundle
import android.text.Spanned
import android.text.TextUtils
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.collection.LruCache
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bytesmith.daydream.databinding.ActivityScreensaverBinding
import kotlinx.coroutines.*
// import org.opencv.android.OpenCVLoader  // COMMENTED OUT - Using GPUImage instead
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * DreamService - Main entry point for the Daydream screensaver.
 * 
 * Refactored from ~1016 lines to use dedicated helper classes:
 * - AnimationController: Handles all animation logic
 * - LayoutManager: Handles layout calculations and positioning
 * - MediaSessionHandler: Handles media display and caching
 * - BatteryMonitor: Handles battery status display
 * - GestureHandler: Handles touch gestures and key events
 * 
 * This class now serves as a coordinator/facade.
 */
class DreamService : android.service.dreams.DreamService() {

    private lateinit var binding: ActivityScreensaverBinding
    private val dreamServiceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isCleaningUp = false
    private var isReceiverRegistered = false

    private lateinit var audioManager: AudioManager
    private lateinit var songGestureDetector: GestureDetector

    private var canvasArtCacheDir: File? = null
    private var iconCacheDir: File? = null

    // Helper controllers
    private lateinit var animationController: AnimationController
    private lateinit var layoutManager: LayoutManager
    private lateinit var mediaSessionHandler: MediaSessionHandler
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var gestureHandler: GestureHandler

    // Layout State (fixedHorizontalMargin used directly; other layout state delegated to LayoutManager)
    private var fixedHorizontalMargin = 0

    // Data State
    private var songCount = 0
    private var lastValidSongInfoHtml: Spanned? = null

    // Icon display
    private val iconCache = ConcurrentHashMap<String, Drawable>()
    private var currentIconStyle = IconStyle.SYSTEM

    // Memory cache for canvas art
    private val memoryCache: LruCache<String, Bitmap>

    init {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / DaydreamSettings.Layout.DEFAULT_MEMORY_CACHE_DIVISOR
        memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    // Layout change listener
    private val layoutChangeListener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        val newWidth = right - left
        val newHeight = bottom - top
        if (layoutManager.onScreenSizeChanged(newWidth, newHeight)) {
            Log.d(TAG, "Screen dimensions changed. Recalculating bounds...")
            updateBoundsAndLayout(forceUpdateScale = true, shouldMove = true)
        }
    }

    companion object {
        private const val TAG = "Daydream"
        private val TIME_WORDS_FORMAT = SimpleDateFormat("hh_mm_a", Locale.getDefault())
        private val DAY_DATE_FORMAT = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

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
            return androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // OPENCV INITIALIZATION COMMENTED OUT - Using GPUImage instead
        // if (!OpenCVLoader.initLocal()) {
        //     Log.e(TAG, "OpenCV failed to initialize. Canvas art will be disabled.")
        // }
        
        fixedHorizontalMargin = resources.getDimensionPixelSize(R.dimen.fixed_horizontal_margin)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        canvasArtCacheDir = File(cacheDir, "canvas_art_cache").also { it.mkdirs() }
    
        // Initialize helper controllers
        animationController = AnimationController(this, dreamServiceScope)
        layoutManager = LayoutManager(this, fixedHorizontalMargin)
        mediaSessionHandler = MediaSessionHandler(this, dreamServiceScope, cacheDir)
        batteryMonitor = BatteryMonitor(this)
        gestureHandler = GestureHandler(this, audioManager, { exitDream() }, { dispatchMediaKeyEvent(it) })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFullscreen = true
        isInteractive = true
    
        // Prevent duplicate receiver registration
        if (isReceiverRegistered) return
    
        // Force System Rotation
        window?.let { win ->
            val params = win.attributes
            params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            win.attributes = params
        }
    
        NotificationService.setDreamActive(true)
    
        val notificationFilter = IntentFilter(NotificationService.UPDATE_NOTIFICATIONS_ACTION)
        ContextCompat.registerReceiver(this, updateNotificationReceiver, notificationFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    
        val mediaFilter = IntentFilter(NotificationService.MEDIA_METADATA_UPDATED_ACTION)
        ContextCompat.registerReceiver(this, mediaMetadataReceiver, mediaFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    
        val songCountFilter = IntentFilter(NotificationService.SONG_COUNT_UPDATED_ACTION)
        ContextCompat.registerReceiver(this, songCountReceiver, songCountFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    
        isReceiverRegistered = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed: orientation=${newConfig.orientation}")

        if (!::binding.isInitialized) return

        // Re-apply media display for new orientation
        NotificationService.lastKnownMediaInfo?.let { info ->
            mediaSessionHandler.clearLastDisplayedInfo()
            updateMediaDisplay(info.cleanedSongString, info.albumArt, info.cacheKey, animate = false)
        } ?: run {
            binding.songName.visibility = View.GONE
            binding.canvasArtImageview.visibility = View.GONE
        }

        updateBoundsAndLayout(forceUpdateScale = true, shouldMove = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onDreamingStarted() {
        super.onDreamingStarted()

        // Reset state for fresh start after orientation change
        isCleaningUp = false
        mediaSessionHandler.clearLastDisplayedInfo()

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        binding = ActivityScreensaverBinding.inflate(inflater)
        setContentView(binding.root)

        adjustBrightness(DaydreamSettings.Appearance.getDreamBrightnessPercent(this))
        setSystemUiVisibility()
        setupUi()
        populateUi()
        startPeriodicUpdates()

        binding.root.postDelayed({
            restoreUiState()
            updateBoundsAndLayout(forceUpdateScale = true, shouldMove = true)
        }, 100)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUi() {
        isInteractive = true
        isFullscreen = true
        binding.contentContainer.pivotX = 0f
        binding.contentContainer.pivotY = 0f

        val songTouchListener = gestureHandler.createSongTouchListener(
            binding.songName, binding.canvasArtImageview
        )

        binding.songName.apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnTouchListener(songTouchListener)
        }

        binding.canvasArtImageview.apply {
            isClickable = true
            isFocusable = true
            setOnTouchListener(songTouchListener)
        }

        val exitTouchListener = gestureHandler.createExitGestureListener(
            binding.songName, binding.canvasArtImageview
        )
        window.decorView.setOnTouchListener(exitTouchListener)
        window.decorView.removeOnLayoutChangeListener(layoutChangeListener)
        window.decorView.addOnLayoutChangeListener(layoutChangeListener)
    }

    private fun populateUi() {
        val prefs = getSharedPreferences("DaydreamSettings", Context.MODE_PRIVATE)
        currentIconStyle = prefs.getInt("notificationIconStyle", IconStyle.SYSTEM)

        val mediaPrefs = getSharedPreferences("MediaPlaybackPrefs", Context.MODE_PRIVATE)
        songCount = mediaPrefs.getInt("songCount", 0)

        if (resources.getBoolean(R.bool.is_tv)) {
            binding.batteryIcon.visibility = View.GONE
            binding.batteryInfo.visibility = View.GONE
        } else {
            batteryMonitor.updateBatteryInfo(binding.batteryInfo, binding.batteryIcon)
        }

        updateTimeAndDateContent()
        adjustContentScale(getTimeInWords(), animate = false)
        updateBatteryIconSize()
        updateSongCountUI(songCount)
        updateNotificationIcons(NotificationService.getPackagesForIconDisplay())

        NotificationService.lastKnownMediaInfo?.let { info ->
            updateMediaDisplay(info.cleanedSongString, info.albumArt, info.cacheKey, animate = false)
        } ?: run {
            binding.songName.visibility = View.GONE
            binding.canvasArtImageview.setImageBitmap(null)
        }
    }

    private fun updateMediaDisplay(songString: String?, albumArt: Bitmap?, cacheKey: String?, animate: Boolean = true) {
        if (!::binding.isInitialized) return

        val (newTitle, newArtist) = mediaSessionHandler.parseSongString(songString)
        val safeCacheKey = cacheKey ?: "null"

        if (!mediaSessionHandler.hasMediaInfoChanged(newTitle, newArtist, safeCacheKey)) {
            return
        }

        mediaSessionHandler.updateLastDisplayedInfo(newTitle, newArtist, safeCacheKey)

        val hasMedia = !songString.isNullOrBlank()

        if (animate) {
            dreamServiceScope.launch {
                animationController.animateMediaFadeOut(binding.songName, binding.canvasArtImageview)
                binding.canvasArtImageview.setImageBitmap(null)

                if (hasMedia) {
                    val formattedText = mediaSessionHandler.formatSongText(newTitle, newArtist)
                    binding.songName.text = formattedText
                    binding.songName.visibility = View.VISIBLE

                    if (albumArt != null && cacheKey != null) {
                        val finalArt = generateAndCacheArt(albumArt, cacheKey)
                        binding.canvasArtImageview.setImageBitmap(finalArt)
                        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        binding.canvasArtImageview.visibility = if (isLandscape) View.VISIBLE else View.GONE
                    } else {
                        binding.canvasArtImageview.visibility = View.GONE
                    }

                    animationController.animateMediaFadeIn(binding.songName, binding.canvasArtImageview)
                } else {
                    binding.songName.visibility = View.GONE
                    binding.canvasArtImageview.visibility = View.GONE
                }

                binding.root.post { updateBoundsAndLayout(forceUpdateScale = false, shouldMove = false) }
            }
        } else {
            if (hasMedia) {
                val formattedText = mediaSessionHandler.formatSongText(newTitle, newArtist)
                binding.songName.text = formattedText
                binding.songName.visibility = View.VISIBLE
                binding.songName.alpha = 1f

                if (albumArt != null && cacheKey != null) {
                    // Set original album art immediately as placeholder
                    binding.canvasArtImageview.setImageBitmap(albumArt)
                    // Then generate artistic version asynchronously
                    processAndDisplayCanvasArt(albumArt, cacheKey)
                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    binding.canvasArtImageview.visibility = if (isLandscape) View.VISIBLE else View.GONE
                    binding.canvasArtImageview.alpha = 1f
                } else {
                    binding.canvasArtImageview.visibility = View.GONE
                }
            } else {
                binding.songName.visibility = View.GONE
                binding.canvasArtImageview.visibility = View.GONE
            }
            binding.root.post { updateBoundsAndLayout(forceUpdateScale = true, shouldMove = true) }
        }
    }

    override fun onDetachedFromWindow() {
        // Ensure receivers are unregistered when window is detached
        // This is critical to prevent IntentReceiver leaks
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(updateNotificationReceiver)
                unregisterReceiver(mediaMetadataReceiver)
                unregisterReceiver(songCountReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receivers not registered during onDetachedFromWindow cleanup.", e)
            }
            isReceiverRegistered = false
        }
        performCleanup()
        super.onDetachedFromWindow()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        performCleanup()
    }

    private fun performCleanup() {
        if (isCleaningUp) return
        isCleaningUp = true

        NotificationService.setDreamActive(false)
        dreamServiceScope.coroutineContext.cancelChildren()
        restoreBrightness()

        if (window != null) {
            window.decorView.removeOnLayoutChangeListener(layoutChangeListener)
            window.decorView.setOnTouchListener(null)
        }

        try {
            unregisterReceiver(updateNotificationReceiver)
            unregisterReceiver(mediaMetadataReceiver)
            unregisterReceiver(songCountReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receivers not registered during cleanup.")
        }
    
        isReceiverRegistered = false
    }

    private fun exitDream() {
        performCleanup()
        finish()
    }

    private fun adjustContentScale(timeStringHtml: String, animate: Boolean = true): Float {
    if (!layoutManager.isFirstLayoutComplete() || window == null) {
    binding.root.post { adjustContentScale(timeStringHtml, animate) }
    return 1.0f
    }
    val screenWidth = window.decorView.width
    val horizontalMargin = fixedHorizontalMargin * 2
    val maxWidth = if (layoutManager.isLandscape()) {
            (screenWidth / 2) - horizontalMargin
        } else {
            screenWidth - horizontalMargin
        }

        if (maxWidth <= 0) return 1.0f

        val timeSpanned = fromHtml(timeStringHtml)
        val tempTextView = TextView(this)
        tempTextView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, binding.timeInWords.textSize)
        tempTextView.typeface = binding.timeInWords.typeface
        tempTextView.text = timeSpanned

        val widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST)
        val heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        tempTextView.measure(widthMeasureSpec, heightMeasureSpec)

        var naturalWidth = tempTextView.measuredWidth.toFloat()
        if (naturalWidth <= 0) {
            naturalWidth = maxWidth.toFloat()
        }

        val scaleFactor = (maxWidth * 0.98f) / naturalWidth

        if (animate) {
            binding.contentContainer.animate()
                .scaleX(scaleFactor)
                .scaleY(scaleFactor)
                .setDuration(300L)
                .start()
        } else {
            binding.contentContainer.scaleX = scaleFactor
            binding.contentContainer.scaleY = scaleFactor
        }
        return scaleFactor
    }

    private val songCountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NotificationService.SONG_COUNT_UPDATED_ACTION) {
                val count = intent.getIntExtra("songCount", 0)
                songCount = count
                updateSongCountUI(count)
            }
        }
    }

    private val mediaMetadataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bundle = intent.extras
            val cleanedSongString = bundle?.getString("cleanedSongString")
            val cacheKey: String? = bundle?.getString("cacheKey")
            val albumArt: Bitmap? = NotificationService.lastKnownMediaInfo?.albumArt

            updateMediaDisplay(cleanedSongString, albumArt, cacheKey, animate = true)
        }
    }

    private fun updateBoundsAndLayout(forceUpdateScale: Boolean, shouldMove: Boolean) {
        if (!::binding.isInitialized || window == null) return

        val rootView = window.decorView
        val screenHeight = rootView.height
        val currentOrientation = resources.configuration.orientation
        val hasMedia = binding.songName.visibility == View.VISIBLE

        layoutManager.updateBoundsAndLayout(
            contentContainer = binding.contentContainer,
            songNameView = binding.songName,
            canvasArtImageview = binding.canvasArtImageview,
            timeInWordsView = binding.timeInWords,
            screenHeight = screenHeight,
            currentOrientation = currentOrientation,
            hasMedia = hasMedia,
            forceUpdateScale = forceUpdateScale,
            shouldMove = shouldMove,
            getTimeInWordsHtml = { getTimeInWords() }
            )
            // isFirstLayoutComplete is now managed solely by LayoutManager
            }

    private fun restoreUiState() {
        NotificationService.lastKnownMediaInfo?.let { info ->
            updateMediaDisplay(info.cleanedSongString, info.albumArt, info.cacheKey, animate = false)
        }
        updateSongCountUI(songCount)
        updateNotificationIcons(NotificationService.getPackagesForIconDisplay())
        // Defer requestLayout to avoid "requestLayout() improperly called during layout pass" warning
        binding.root.post { binding.root.requestLayout() }
    }

    private fun startPeriodicUpdates() {
        dreamServiceScope.coroutineContext.cancelChildren()
        dreamServiceScope.launch {
            while (isActive) {
                if (!resources.getBoolean(R.bool.is_tv)) {
                    batteryMonitor.updateBatteryInfo(binding.batteryInfo, binding.batteryIcon)
                }
                delay(DaydreamSettings.Timing.getBatteryUpdateIntervalMs(this@DreamService))
            }
        }
        startMinuteChangeSequenceLoop()
    }

    private fun startMinuteChangeSequenceLoop() {
        dreamServiceScope.launch {
            while (isActive) {
                val calendar = Calendar.getInstance()
                val msToNextBoundary = (60 - calendar.get(Calendar.SECOND)) * 1000L - calendar.get(Calendar.MILLISECOND)
                val delayBeforeFadeOut = msToNextBoundary - animationController.transitionOutDurationMs
        
                if (delayBeforeFadeOut < 0) {
                    // Too close to minute boundary - wait for the next minute instead
                    delay(msToNextBoundary + 60000L - animationController.transitionOutDurationMs)
                } else {
                    delay(delayBeforeFadeOut)
                }

                animationController.animateAlphaManual(binding.contentContainer, isFadeOut = true)
                binding.contentContainer.alpha = 0f

                // Safety Wait
                while (Calendar.getInstance().get(Calendar.SECOND) == 59) {
                    delay(10)
                }

                updateTimeAndDateContent()
                updateBoundsAndLayout(forceUpdateScale = true, shouldMove = true)

                animationController.animateAlphaManual(binding.contentContainer, isFadeOut = false)
                delay(DaydreamSettings.Timing.getPostAnimationDelayMs(this@DreamService))
            }
        }
    }

    private fun moveToRandomPosition() {
        layoutManager.moveToRandomPosition(binding.contentContainer)
    }

    private fun adjustBrightness(brightnessLevel: Int) {
        BrightnessService.changeBrightness(this, brightnessLevel)
    }

    private fun restoreBrightness() {
        BrightnessService.restoreOriginalBrightness(this)
    }

    private fun updateTimeAndDateContent() {
        binding.timeInWords.text = fromHtml(getTimeInWords())
        binding.dayDate.text = getDayDate()
    }

    private fun getTimeInWords(): String {
        val calendar = Calendar.getInstance()
        val timeKey = "time_${TIME_WORDS_FORMAT.format(calendar.time).replace(":", "_").uppercase()}"
        val resId = resources.getIdentifier(timeKey, "string", packageName)
        return if (resId == 0) "Time Unknown" else getString(resId)
    }

    private fun getDayDate(): String {
        return DAY_DATE_FORMAT.format(Calendar.getInstance().time)
    }

    private fun updateBatteryInfo() {
        batteryMonitor.updateBatteryInfo(binding.batteryInfo, binding.batteryIcon)
    }

    private fun updateNotificationIcons(notificationPackages: Set<String>?) {
        if (!::binding.isInitialized) return
        val container = binding.notificationIconContainer

        if (currentIconStyle == IconStyle.OFF) {
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
            }
        }
    }

    private fun fetchAndCacheNotificationIcon(packageName: String): Drawable? {
        val cacheKey = "$packageName-$currentIconStyle"
        if (iconCache.containsKey(cacheKey)) {
            return iconCache[cacheKey]
        }
        try {
            val finalDrawable: Drawable? = when (currentIconStyle) {
                IconStyle.SYSTEM -> {
                    val systemIcon = NotificationService.getSmallIconForPackage(this, packageName)
                    systemIcon?.mutate()?.apply { setTint(Color.WHITE) }
                }
                IconStyle.MONOCHROME -> {
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
                else -> null
            }
            finalDrawable?.let {
                iconCache[cacheKey] = it
                cacheIcon(packageName, it)
            }
            return finalDrawable
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get icon for $packageName", e)
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
            Log.e(TAG, "Failed to cache icon for $packageName", e)
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
            binding.canvasArtImageview.setImageBitmap(cachedBitmap)
            binding.root.post { updateBoundsAndLayout(forceUpdateScale = false, shouldMove = false) }
            return
        }
        dreamServiceScope.launch {
            val finalBitmap = generateAndCacheArt(sourceBitmap, cacheKey)
            finalBitmap?.let {
                memoryCache.put(cacheKey, it)
                binding.canvasArtImageview.setImageBitmap(it)
                binding.root.post { updateBoundsAndLayout(forceUpdateScale = false, shouldMove = false) }
            }
        }
    }

    private suspend fun generateAndCacheArt(sourceBitmap: Bitmap, cacheKey: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val cachedInMemory = memoryCache.get(cacheKey)
            if (cachedInMemory != null) return@withContext cachedInMemory

            val artCacheDir = canvasArtCacheDir ?: File(cacheDir, "canvas_art_cache").also { it.mkdirs() }
            val cachedFile = File(artCacheDir, "$cacheKey.png")
            if (cachedFile.exists()) {
                return@withContext BitmapFactory.decodeFile(cachedFile.absolutePath)?.also {
                    memoryCache.put(cacheKey, it)
                }
            }

            val generatedArt = ArtisticRenderer.create(this@DreamService, sourceBitmap)
            generatedArt?.let {
                try {
                    FileOutputStream(cachedFile).use { out ->
                        it.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to save canvas art to cache", e)
                }
                memoryCache.put(cacheKey, it)
            }
            return@withContext generatedArt
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
        if (gestureHandler.handleVolumeKey(event)) {
            return true
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

    private fun updateBatteryIconSize() {
        binding.batteryIcon.layoutParams.apply {
            width = resources.getDimensionPixelSize(R.dimen.battery_icon_width)
            height = resources.getDimensionPixelSize(R.dimen.battery_icon_height)
        }
        // Defer requestLayout to avoid "requestLayout() improperly called during layout pass" warning
        binding.batteryIcon.post { binding.batteryIcon.requestLayout() }
    }

    private fun dispatchMediaKeyEvent(@Suppress("UNUSED_PARAMETER") keyCode: Int) {
        sendBroadcast(Intent(NotificationService.MEDIA_INFO_REQUEST_ACTION))
    }
}