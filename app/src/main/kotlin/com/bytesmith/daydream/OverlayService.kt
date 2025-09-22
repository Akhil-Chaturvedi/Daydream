package com.bytesmith.daydream

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Spanned
import android.text.TextUtils
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.text.HtmlCompat
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * A foreground service that displays the screensaver UI as a system overlay.
 *
 * This service is responsible for:
 * - Creating and managing a window that floats on top of all other apps.
 * - Inflating the screensaver layout and handling all its UI updates.
 * - Running as a foreground service to prevent the OS from killing it.
 * - Cleaning up all resources (window, receivers, etc.) when it's stopped.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    // All UI elements and managers are declared here.
    private lateinit var timeInWordsTextView: TextView
    private lateinit var dayDateTextView: TextView
    private lateinit var batteryInfoTextView: TextView
    private lateinit var songCountTextView: TextView
    private lateinit var songNameTextView: TextView
    private lateinit var batteryIconImageView: ImageView
    private lateinit var notificationIconContainer: LinearLayout
    private lateinit var audioManager: AudioManager
    private lateinit var songGestureDetector: GestureDetector
    private lateinit var exitGestureDetector: GestureDetector

    private val handler = Handler(Looper.getMainLooper())
    private var shiftCount = 0
    private var isUpdating = false

    // All Runnables for periodic updates live here.
    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            if (isUpdating) {
                updateTimeInWords()
                handler.postDelayed(this, UPDATE_INTERVAL)
            }
        }
    }
    private val updateDateRunnable = object : Runnable {
        override fun run() {
            if (isUpdating) {
                updateDayDateTextView()
                handler.postDelayed(this, UPDATE_INTERVAL)
            }
        }
    }
    private val updateBatteryRunnable = object : Runnable {
        override fun run() {
            if (isUpdating) {
                updateBatteryInfo()
                handler.postDelayed(this, BATTERY_UPDATE_INTERVAL)
            }
        }
    }
    private val shiftTextViewsRunnable = object : Runnable {
        override fun run() {
            if (isUpdating) {
                shiftTextViews()
                handler.postDelayed(this, SHIFT_DURATION)
            }
        }
    }

    // Companion object for static-like access from other classes (e.g., NotificationService).
    companion object {
        private const val TAG = "OverlayService"
        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL = 1000L
        private const val BATTERY_UPDATE_INTERVAL = 60000L
        private const val SHIFT_DURATION = 10000L
        private const val SHIFT_AMOUNT = 100
        private const val MAX_SHIFTS = 5

        private var instanceRef: WeakReference<OverlayService>? = null
        private var songCount = 0
        private var lastValidSongInfoHtml: Spanned? = null

        private val TIME_WORDS_FORMAT = SimpleDateFormat("hh_mm_a", Locale.getDefault())
        private val DAY_DATE_FORMAT = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

        @JvmStatic
        fun updateSongInfo(songName: String?) {
            instanceRef?.get()?.handler?.post {
                instanceRef?.get()?.handleSongInfoUpdate(songName)
            }
        }

        @JvmStatic
        fun updateSongCount(count: Int) {
            songCount = count
            instanceRef?.get()?.handler?.post {
                instanceRef?.get()?.updateSongCountUI(count)
            }
        }

        private fun escapeHtml(text: String?): String = TextUtils.htmlEncode(text ?: "")

        private fun capitalizeWords(str: String?): String {
            if (str.isNullOrEmpty()) return ""
            return str.split(Regex("\\s+")).joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }
        }

        private fun fromHtml(html: String): Spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("InflateParams")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")
        instanceRef = WeakReference(this)

        // 1. Inflate the layout and initialize WindowManager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.activity_screensaver, null)

        // 2. Define the parameters for the overlay window
        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        // 3. Add the view to the window
        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay view to window manager", e)
            stopSelf() // If we can't add the view, the service is useless.
            return
        }

        // 4. Set up everything, now that the view is attached
        initializeAll()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OverlayService onStartCommand")
        // Elevate the service to a foreground service to prevent it from being killed.
        startForeground(FOREGROUND_NOTIFICATION_ID, createNotification())
        return START_NOT_STICKY // If killed, don't restart automatically.
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OverlayService onDestroy")
        isUpdating = false
        handler.removeCallbacksAndMessages(null)

        try {
            unregisterReceiver(songNameReceiver)
            unregisterReceiver(updateNotificationReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receivers were not registered.")
        }

        stopNotificationService()
        saveShiftCountToPreferences()
        restoreBrightness()

        if (overlayView != null) {
            windowManager.removeView(overlayView)
        }

        overlayView = null
        instanceRef?.clear()
    }

    private fun createNotification(): Notification {
        val channelId = "DaydreamOverlayServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Daydream Service", NotificationManager.IMPORTANCE_MIN)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Daydream is active")
            .setContentText("Your screensaver is running.")
            .setSmallIcon(R.mipmap.ic_launcher) // You MUST have an icon
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /**
     * A single setup function called from onCreate after the view is added.
     */
    private fun initializeAll() {
        initializeViews()
        initializeLayoutParams()
        loadShiftCountFromPreferences()
        incrementShiftCount()
        applyShiftPosition()
        updateBatteryInfo()
        updateTimeInWords()
        updateDayDateTextView()
        updateBatteryIconSize()
        registerReceivers()
        adjustBrightness(10)
        startNotificationService()
        updateNotificationIcons(NotificationService.getPackagesForIconDisplay())
        sendBroadcast(Intent(NotificationService.MEDIA_INFO_REQUEST_ACTION))
        startPeriodicUpdates()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeViews() {
        overlayView?.let { view ->
            timeInWordsTextView = view.findViewById(R.id.time_in_words)
            dayDateTextView = view.findViewById(R.id.day_date)
            batteryInfoTextView = view.findViewById(R.id.battery_info)
            batteryIconImageView = view.findViewById(R.id.battery_icon)
            songCountTextView = view.findViewById(R.id.song_count)
            songNameTextView = view.findViewById(R.id.song_name)
            notificationIconContainer = view.findViewById(R.id.notification_icon_container)

            songGestureDetector = GestureDetector(this, SongGestureListener())
            songNameTextView.apply {
                visibility = View.GONE
                isClickable = true
                isFocusable = true
                setOnTouchListener { _, event -> songGestureDetector.onTouchEvent(event) }
            }

            exitGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    Log.d(TAG, "Exit gesture detected. Stopping service.")
                    stopSelf() // Stop the service itself on double tap
                    return true
                }
            })

            view.setOnTouchListener { _, event ->
                // Let the exit detector handle the event first.
                exitGestureDetector.onTouchEvent(event)
                // Consume all touch events so they don't pass through to the underlying screen.
                true
            }

            updateSongCountUI(songCount)
        }
    }

    private fun initializeLayoutParams() {
        // This function is still useful for setting initial layout rules if needed,
        // but most layout is handled by the XML. You can adjust margins dynamically here.
    }
    
    private fun registerReceivers() {
        registerReceiver(songNameReceiver, IntentFilter(NotificationService.SONG_NAME_UPDATED_ACTION))
        registerReceiver(updateNotificationReceiver, IntentFilter(NotificationService.UPDATE_NOTIFICATIONS_ACTION))
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
            if (Math.abs(diffX) > Math.abs(e2.y - e1.y) && Math.abs(diffX) > swipeThreshold && Math.abs(velocityX) > swipeVelocityThreshold) {
                if (diffX > 0) dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS) else dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
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
        handler.post(updateBatteryRunnable)
        handler.post(shiftTextViewsRunnable)
    }

    private fun handleSongInfoUpdate(songName: String?) {
        val textView = songNameTextView
        if (!songName.isNullOrBlank()) {
            val formattedText = if (songName.contains('\n')) {
                val (title, artist) = songName.split('\n', limit = 2)
                fromHtml("<b><font size='+4'>${escapeHtml(capitalizeWords(title))}</font></b><br>${escapeHtml(capitalizeWords(artist))}")
            } else {
                fromHtml("<b><font size='+4'>${escapeHtml(capitalizeWords(songName))}</font></b>")
            }
            textView.text = formattedText
            textView.visibility = View.VISIBLE
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
    
    // --- All other helper functions from the original DreamService are moved here ---

    private fun adjustBrightness(brightnessLevel: Int) = BrightnessService.changeBrightness(this, brightnessLevel)
    private fun restoreBrightness() = BrightnessService.restoreOriginalBrightness(this)

    private fun updateTimeInWords() {
        if (::timeInWordsTextView.isInitialized) {
            timeInWordsTextView.text = fromHtml(getTimeInWords())
        }
    }

    private fun getTimeInWords(): String {
        val calendar = Calendar.getInstance()
        val timeKey = "time_${TIME_WORDS_FORMAT.format(calendar.time).replace(":", "_").uppercase()}"
        val resId = resources.getIdentifier(timeKey, "string", packageName)
        return if (resId == 0) getString(R.string.default_time_string) else getString(resId)
    }

    private fun updateDayDateTextView() {
        if (::dayDateTextView.isInitialized) {
            dayDateTextView.text = getDayDate()
        }
    }

    private fun getDayDate(): String = DAY_DATE_FORMAT.format(Calendar.getInstance().time)

    private fun updateBatteryInfo() {
        if (::batteryInfoTextView.isInitialized && ::batteryIconImageView.isInitialized) {
            val batteryLevel = getBatteryLevel()
            batteryInfoTextView.text = "$batteryLevel%"
            batteryIconImageView.setImageResource(getBatteryIconResId(batteryLevel))
        }
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
        if (!::notificationIconContainer.isInitialized) return
        notificationIconContainer.post {
            notificationIconContainer.removeAllViews()
            notificationPackages?.forEach { packageName ->
                try {
                    val icon = packageManager.getApplicationIcon(packageName)
                    val iconView = ImageView(this@OverlayService).apply {
                        setImageDrawable(icon)
                        layoutParams = LinearLayout.LayoutParams(
                            resources.getDimensionPixelSize(R.dimen.notification_icon_size),
                            resources.getDimensionPixelSize(R.dimen.notification_icon_size)
                        ).apply { marginEnd = resources.getDimensionPixelSize(R.dimen.notification_icon_margin) }
                    }
                    notificationIconContainer.addView(iconView)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Could not fetch icon for package: $packageName")
                }
            }
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
        if (::timeInWordsTextView.isInitialized) {
            (timeInWordsTextView.layoutParams as? RelativeLayout.LayoutParams)?.apply {
                topMargin = shiftCount * SHIFT_AMOUNT
                timeInWordsTextView.layoutParams = this
            }
        }
    }
    
    private val songNameReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NotificationService.SONG_NAME_UPDATED_ACTION) {
                updateSongInfo(intent.getStringExtra("songName"))
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
        if (!::songCountTextView.isInitialized) return
        songCountTextView.apply {
            visibility = if (count > 0) {
                text = "Songs: $count"
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    private fun startNotificationService() = startService(Intent(this, NotificationService::class.java))
    private fun stopNotificationService() = stopService(Intent(this, NotificationService::class.java))
    
    private fun updateBatteryIconSize() {
        if (::batteryIconImageView.isInitialized) {
            batteryIconImageView.layoutParams.apply {
                width = resources.getDimensionPixelSize(R.dimen.battery_icon_width)
                height = resources.getDimensionPixelSize(R.dimen.battery_icon_height)
            }
            batteryIconImageView.requestLayout()
        }
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
        sendBroadcast(Intent(NotificationService.MEDIA_INFO_REQUEST_ACTION))
    }
}