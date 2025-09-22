package com.bytesmith.daydream

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.TextUtils
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.text.HtmlCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DreamService : android.service.dreams.DreamService() {

private lateinit var timeInWordsTextView: TextView
private lateinit var dayDateTextView: TextView
private lateinit var batteryInfoTextView: TextView
private lateinit var songCountTextView: TextView
private lateinit var songNameTextView: TextView
private lateinit var batteryIconImageView: ImageView
private lateinit var notificationIconContainer: LinearLayout
private lateinit var audioManager: AudioManager
private lateinit var songGestureDetector: GestureDetector

private var iconCacheDir: File? = null
private val iconCache = mutableMapOf<String, Drawable>()
private val handler = Handler(Looper.getMainLooper())
private var shiftCount = 0
private var isUpdating = false

// A Runnable object that references itself needs to be an 'object' expression
private val updateTimeRunnable = object : Runnable {
    override fun run() {
        updateTimeInWords()
        handler.postDelayed(this, UPDATE_INTERVAL)
    }
}
private val updateDateRunnable = object : Runnable {
    override fun run() {
        updateDayDateTextView()
        handler.postDelayed(this, UPDATE_INTERVAL)
    }
}
private val updateSongCountRunnable = object : Runnable {
    override fun run() {
        // Note: This runnable might be redundant if update is only triggered by NotificationService
        updateSongCountUI(songCount)
        handler.postDelayed(this, UPDATE_INTERVAL)
    }
}
private val updateBatteryRunnable = object : Runnable {
    override fun run() {
        updateBatteryInfo()
        handler.postDelayed(this, BATTERY_UPDATE_INTERVAL)
    }
}
private val shiftTextViewsRunnable = object : Runnable {
    override fun run() {
        shiftTextViews()
        handler.postDelayed(this, SHIFT_DURATION)
    }
}

// Companion Object holds static members from the Java class.
companion object {
    private const val TAG = "DreamService"
    private const val UPDATE_INTERVAL = 1000L // Use 'L' for Long constants
    private const val BATTERY_UPDATE_INTERVAL = 60000L
    private const val SHIFT_DURATION = 10000L
    private const val SHIFT_AMOUNT = 100
    private const val MAX_SHIFTS = 5

    private var instanceRef: WeakReference<DreamService>? = null
    private var songCount = 0
    private var lastValidSongInfoHtml: Spanned? = null

    // Reusable formatters
    private val TIME_WORDS_FORMAT = SimpleDateFormat("hh_mm_a", Locale.getDefault())
    private val DAY_DATE_FORMAT = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

    // @JvmStatic is crucial so this method can be called from Java (NotificationService)
    @JvmStatic
    fun updateSongInfo(songName: String?) {
        val instance = instanceRef?.get()
        // FIXED ERROR #1: Changed check from isAttachedToWindow to window != null
        if (instance == null || instance.window == null) {
            Log.e(TAG, "DreamService instance is null or not attached in updateSongInfo")
            return
        }

        instance.handler.post {
            val textView = instance.songNameTextView
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
    }

    // @JvmStatic allows this to be called from NotificationService.java
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
    instanceRef = WeakReference(this)
    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
}

@SuppressLint("ClickableViewAccessibility")
override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    instanceRef = WeakReference(this)
    // Register receivers
    registerReceiver(songNameReceiver, IntentFilter(NotificationService.SONG_NAME_UPDATED_ACTION))
    registerReceiver(updateNotificationReceiver, IntentFilter(NotificationService.UPDATE_NOTIFICATIONS_ACTION))

    initializeDreamService()

    val exitGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // FIXED ERROR #2: Changed finishAndRemoveTask() to finish()
            finish()
            return true
        }
        override fun onDown(e: MotionEvent): Boolean {
            return !isPointInsideView(e.rawX, e.rawY, songNameTextView)
        }
    })

    window.decorView.setOnTouchListener { _, event ->
        exitGestureDetector.onTouchEvent(event) || !isPointInsideView(event.rawX, event.rawY, songNameTextView)
    }

    // --- [CHANGE APPLIED] ---
    // START THE UPDATES HERE, AFTER EVERYTHING IS INITIALIZED
    startPeriodicUpdates()
}

override fun onDreamingStarted() {
    super.onDreamingStarted()
    // --- [CHANGE APPLIED] ---
    // The call to startPeriodicUpdates() has been moved to onAttachedToWindow().
}

override fun onDreamingStopped() {
    super.onDreamingStopped()
    isUpdating = false
    handler.removeCallbacksAndMessages(null)
    stopNotificationService()
    saveShiftCountToPreferences()
}

override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    isUpdating = false
    handler.removeCallbacksAndMessages(null)
    stopNotificationService()
    restoreBrightness()
    try {
        unregisterReceiver(songNameReceiver)
        unregisterReceiver(updateNotificationReceiver)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Receivers not registered, skipping unregister.")
    }
    window.decorView.setOnTouchListener(null)
}

private fun initializeDreamService() {
    isInteractive = true
    isFullscreen = true
    setContentView(R.layout.activity_screensaver)
    adjustBrightness(10)
    setSystemUiVisibility()
    startNotificationService()
    initializeViews()
    initializeLayoutParams()
    loadShiftCountFromPreferences()
    incrementShiftCount()
    applyShiftPosition()
    updateBatteryInfo()
    updateTimeInWords()
    updateBatteryIconSize()
    updateDayDateTextView()
    updateNotificationIcons(NotificationService.getPackagesForIconDisplay())
    sendBroadcast(Intent(NotificationService.MEDIA_INFO_REQUEST_ACTION))
}

@SuppressLint("ClickableViewAccessibility")
private fun initializeViews() {
    timeInWordsTextView = findViewById(R.id.time_in_words)
    dayDateTextView = findViewById(R.id.day_date)
    batteryInfoTextView = findViewById(R.id.battery_info)
    batteryIconImageView = findViewById(R.id.battery_icon)
    songCountTextView = findViewById(R.id.song_count)
    songNameTextView = findViewById(R.id.song_name)
    notificationIconContainer = findViewById(R.id.notification_icon_container)

    songNameTextView.apply {
        visibility = View.GONE
        isClickable = true
        isFocusable = true
        songGestureDetector = GestureDetector(this@DreamService, SongGestureListener())
        setOnTouchListener { _, event -> songGestureDetector.onTouchEvent(event) }
    }
    updateSongCountUI(songCount)
}

private fun initializeLayoutParams() {
    (timeInWordsTextView.layoutParams as RelativeLayout.LayoutParams).apply {
        setMargins(
            resources.getDimensionPixelSize(R.dimen.margin_start_time_in_words),
            resources.getDimensionPixelSize(R.dimen.margin_top_time_in_words),
            resources.getDimensionPixelSize(R.dimen.margin_right_time_in_words),
            resources.getDimensionPixelSize(R.dimen.margin_bottom_time_in_words)
        )
    }
    (dayDateTextView.layoutParams as RelativeLayout.LayoutParams).apply {
        addRule(RelativeLayout.BELOW, R.id.time_in_words)
        setMargins(
            resources.getDimensionPixelSize(R.dimen.margin_start_time_in_words),
            resources.getDimensionPixelSize(R.dimen.margin_top_day_date),
            resources.getDimensionPixelSize(R.dimen.margin_right_day_date),
            resources.getDimensionPixelSize(R.dimen.margin_bottom_day_date)
        )
    }
    (batteryIconImageView.layoutParams as RelativeLayout.LayoutParams).apply {
        addRule(RelativeLayout.BELOW, R.id.day_date)
        addRule(RelativeLayout.ALIGN_PARENT_LEFT)
        setMargins(
            resources.getDimensionPixelSize(R.dimen.margin_start_battery_icon),
            resources.getDimensionPixelSize(R.dimen.margin_top_battery_icon),
            0,
            0
        )
    }
    (batteryInfoTextView.layoutParams as RelativeLayout.LayoutParams).apply {
        addRule(RelativeLayout.BELOW, R.id.day_date)
        addRule(RelativeLayout.END_OF, R.id.battery_icon)
        addRule(RelativeLayout.ALIGN_TOP, R.id.battery_icon)
        addRule(RelativeLayout.ALIGN_BOTTOM, R.id.battery_icon)
        setMargins(0, 0, resources.getDimensionPixelSize(R.dimen.margin_right_battery_info), 0)
    }
    batteryInfoTextView.gravity = android.view.Gravity.CENTER_VERTICAL
    (songCountTextView.layoutParams as RelativeLayout.LayoutParams).apply {
        removeRule(RelativeLayout.ALIGN_BASELINE)
        addRule(RelativeLayout.END_OF, R.id.battery_info)
        addRule(RelativeLayout.ALIGN_TOP, R.id.battery_info)
        addRule(RelativeLayout.ALIGN_BOTTOM, R.id.battery_info)
        setMargins(resources.getDimensionPixelSize(R.dimen.margin_start_song_count), 0, 0, 0)
    }
    songCountTextView.gravity = android.view.Gravity.CENTER_VERTICAL
}

private inner class SongGestureListener : GestureDetector.SimpleOnGestureListener() {
    private val swipeThreshold = 100
    private val swipeVelocityThreshold = 100

    override fun onDown(e: MotionEvent): Boolean = true

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        Log.d(TAG, "Song name TextView single tap detected.")
        dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        return true
    }

    // FIXED ERROR #3: Added '?' to make e1 nullable, matching the parent method signature
    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        e1 ?: return false // If e1 is null, can't calculate a fling
        val diffX = e2.x - e1.x
        if (Math.abs(diffX) > Math.abs(e2.y - e1.y) &&
            Math.abs(diffX) > swipeThreshold &&
            Math.abs(velocityX) > swipeVelocityThreshold
        ) {
            if (diffX > 0) {
                Log.d(TAG, "Right swipe detected on song text.")
                dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            } else {
                Log.d(TAG, "Left swipe detected on song text.")
                dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            return true
        }
        return false
    }
}

private fun startPeriodicUpdates() {
    if (isUpdating) return
    isUpdating = true
    handler.post(updateTimeRunnable)
    handler.post(updateDateRunnable)
    handler.post(updateSongCountRunnable)
    handler.post(updateBatteryRunnable)
    handler.post(shiftTextViewsRunnable)
}

private fun adjustBrightness(brightnessLevel: Int) {
    BrightnessService.changeBrightness(this, brightnessLevel)
}

private fun restoreBrightness() {
    Log.d(TAG, "Attempting to restore original brightness")
    BrightnessService.restoreOriginalBrightness(this)
}

private fun updateTimeInWords() {
    timeInWordsTextView.text = fromHtml(getTimeInWords())
}

private fun getTimeInWords(): String {
    val calendar = Calendar.getInstance()
    val timeKey = "time_${TIME_WORDS_FORMAT.format(calendar.time).replace(":", "_").uppercase()}"
    val resId = resources.getIdentifier(timeKey, "string", packageName)
    return if (resId == 0) getString(R.string.default_time_string) else getString(resId)
}

private fun updateDayDateTextView() {
    dayDateTextView.text = getDayDate()
}

private fun getDayDate(): String {
    return DAY_DATE_FORMAT.format(Calendar.getInstance().time)
}

private fun updateBatteryInfo() {
    val batteryLevel = getBatteryLevel()
    batteryInfoTextView.text = "$batteryLevel%" // String templates are cleaner
    batteryIconImageView.setImageResource(getBatteryIconResId(batteryLevel))
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
    notificationIconContainer.post {
        notificationIconContainer.removeAllViews()
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
                notificationIconContainer.addView(iconView)
            } ?: Log.w(TAG, "Could not fetch icon for package: $packageName")
        }
    }
}

private fun fetchAndCacheNotificationIcon(packageName: String): Drawable? {
    return try {
        packageManager.getApplicationIcon(packageName).also { cacheIcon(packageName, it) }
    } catch (e: PackageManager.NameNotFoundException) { // FIXED ERROR #4: Used the fully qualified exception type
        Log.e(TAG, "Failed to get icon for package: $packageName", e)
        null
    }
}

private fun cacheIcon(packageName: String, icon: Drawable) {
    if (iconCacheDir == null) {
        iconCacheDir = File(cacheDir, "icon_cache").also {
            if (!it.exists()) it.mkdirs()
        }
    }
    val iconFile = File(iconCacheDir, "$packageName.png")
    if (iconFile.exists() || icon !is BitmapDrawable) return

    try {
        FileOutputStream(iconFile).use { fos ->
            icon.bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
    } catch (e: IOException) {
        Log.e(TAG, "Failed to cache icon for package: $packageName", e)
    }
}

private fun shiftTextViews() {
    incrementShiftCount()
    applyShiftPosition()
}

private fun incrementShiftCount() {
    shiftCount = (shiftCount + 1) % MAX_SHIFTS
}

private fun applyShiftPosition() {
    (timeInWordsTextView.layoutParams as? RelativeLayout.LayoutParams)?.apply {
        topMargin = shiftCount * SHIFT_AMOUNT
        timeInWordsTextView.layoutParams = this
    } ?: Log.e(TAG, "TimeInWordsTextView layout params are not RelativeLayout.LayoutParams")
}

private val songNameReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == NotificationService.SONG_NAME_UPDATED_ACTION) {
            val songName = intent.getStringExtra("songName")
            updateSongInfo(songName)
        }
    }
}
private val updateNotificationReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == NotificationService.UPDATE_NOTIFICATIONS_ACTION) {
            updateNotificationIcons(NotificationService.getPackagesForIconDisplay())
        }
    }
}

private fun updateSongCountUI(count: Int) {
    if (!::songCountTextView.isInitialized) {
        return
    }

    handler.post {
        songCountTextView.apply {
            if (count > 0) {
                text = "Songs: $count"
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
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
    batteryIconImageView.layoutParams.apply {
        width = resources.getDimensionPixelSize(R.dimen.battery_icon_width)
        height = resources.getDimensionPixelSize(R.dimen.battery_icon_height)
    }
    batteryIconImageView.requestLayout()
}

private fun loadShiftCountFromPreferences() {
    val prefs = getSharedPreferences("DreamServicePrefs", Context.MODE_PRIVATE)
    shiftCount = prefs.getInt("shiftCount", 0)
}

private fun saveShiftCountToPreferences() {
    getSharedPreferences("DreamServicePrefs", Context.MODE_PRIVATE).edit {
        putInt("shiftCount", shiftCount)
    }
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