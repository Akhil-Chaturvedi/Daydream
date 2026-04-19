package com.bytesmith.daydream

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Handles gesture detection and media key dispatching.
 * Extracted from DreamService for better separation of concerns.
 */
class GestureHandler(
    private val context: Context,
    private val audioManager: AudioManager,
    private val onExitDream: () -> Unit,
    private val onMediaKeyEvent: (Int) -> Unit
) {
    private val swipeThreshold: Int
    private val swipeVelocityThreshold: Int

    init {
        swipeThreshold = DaydreamSettings.Gestures.getSwipeThreshold(context)
        swipeVelocityThreshold = DaydreamSettings.Gestures.getSwipeVelocityThreshold(context)
    }

    /**
     * Creates a gesture detector for song navigation.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun createSongTouchListener(
        songNameView: View,
        canvasArtView: View
    ): View.OnTouchListener {
        val gestureDetector = GestureDetector(context, SongGestureListener(songNameView, canvasArtView))
        return View.OnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    /**
     * Creates a gesture detector for exit (double tap anywhere else).
     */
    fun createExitGestureListener(
        songNameView: View,
        canvasArtView: View
    ): View.OnTouchListener {
        val exitGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onExitDream()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                val isTouchingSongName = isPointInsideView(e.rawX, e.rawY, songNameView)
                val isTouchingAlbumArt = isPointInsideView(e.rawX, e.rawY, canvasArtView)
                return !isTouchingSongName && !isTouchingAlbumArt
            }
        })
        return View.OnTouchListener { _, event ->
            exitGestureDetector.onTouchEvent(event)
        }
    }

    /**
     * Dispatches a media key event.
     */
    fun dispatchMediaKeyEvent(keyCode: Int) {
        val eventTime = android.os.SystemClock.uptimeMillis()
        val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    /**
     * Handles volume key events.
     */
    fun handleVolumeKey(event: KeyEvent): Boolean {
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
        return false
    }

    private fun isPointInsideView(x: Float, y: Float, view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val viewX = location[0]
        val viewY = location[1]
        return (x > viewX && x < (viewX + view.width)) && (y > viewY && y < (viewY + view.height))
    }

    /**
     * Song gesture listener for play/pause and swipe navigation.
     */
    private inner class SongGestureListener(
        private val songNameView: View,
        private val canvasArtView: View
    ) : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            e1 ?: return false
            val diffX = e2.x - e1.x
            if (abs(diffX) > abs(e2.y - e1.y) &&
                abs(diffX) > swipeThreshold &&
                abs(velocityX) > swipeVelocityThreshold
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
}