package com.bytesmith.daydream

import android.content.res.Configuration
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.text.HtmlCompat
import kotlin.random.Random

/**
 * Handles layout calculations and positioning for the DreamService.
 * Extracted from DreamService for better separation of concerns.
 */
class LayoutManager(
    private val context: android.content.Context,
    private val fixedHorizontalMargin: Int
) {
    private var maxVerticalMargin = 0
    private var isFirstLayoutComplete = false
    private var isLandscape = false
    private var lastScreenWidth = 0
    private var lastScreenHeight = 0

    /**
     * Called when screen dimensions change.
     * Returns true if layout was recalculated.
     */
    fun onScreenSizeChanged(newWidth: Int, newHeight: Int): Boolean {
        if (newWidth != lastScreenWidth || newHeight != lastScreenHeight) {
            lastScreenWidth = newWidth
            lastScreenHeight = newHeight
            return true
        }
        return false
    }

    /**
     * Updates layout based on current orientation and content.
     */
    fun updateBoundsAndLayout(
        contentContainer: View,
        songNameView: View,
        canvasArtImageview: View,
        timeInWordsView: TextView,
        screenHeight: Int,
        currentOrientation: Int,
        hasMedia: Boolean,
        forceUpdateScale: Boolean,
        shouldMove: Boolean,
        getTimeInWordsHtml: () -> String
    ) {
        // Use System Configuration to detect orientation
        isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE

        // 1. RESET LAYOUT RULES
        val containerParams = contentContainer.layoutParams as RelativeLayout.LayoutParams
        containerParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        containerParams.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT)
        containerParams.removeRule(RelativeLayout.CENTER_HORIZONTAL)
        containerParams.removeRule(RelativeLayout.CENTER_VERTICAL)

        // Always anchor Top-Left
        containerParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
        containerParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT)

        // 2. SCALE CONTENT
        val currentScale = if (forceUpdateScale) {
            adjustContentScale(contentContainer, timeInWordsView, getTimeInWordsHtml)
        } else {
            contentContainer.scaleX
        }

        val scaledClockHeight = contentContainer.height * currentScale

        // 3. CALCULATE MARGINS
        var reservedBottomSpace = 0
        if (songNameView.visibility == View.VISIBLE && !isLandscape) {
            val songNameHeight = songNameView.height
            val buffer = (screenHeight * 0.05).toInt()
            reservedBottomSpace = songNameHeight + buffer
        }

        val availableVerticalSpace = screenHeight - scaledClockHeight - reservedBottomSpace
        maxVerticalMargin = availableVerticalSpace.toInt().coerceAtLeast(0)

        // 4. POSITION MEDIA INFO
        val songParams = songNameView.layoutParams as RelativeLayout.LayoutParams
        if (isLandscape) {
            canvasArtImageview.visibility = if (hasMedia) View.VISIBLE else View.GONE
            songParams.removeRule(RelativeLayout.CENTER_HORIZONTAL)
            songParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            songParams.addRule(RelativeLayout.RIGHT_OF, R.id.center_anchor)
            songParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
        } else {
            canvasArtImageview.visibility = View.GONE
            songParams.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            songParams.removeRule(RelativeLayout.RIGHT_OF)
            songParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            songParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
        }
        songNameView.layoutParams = songParams

        // 5. APPLY
        contentContainer.layoutParams = containerParams
        if (!isFirstLayoutComplete || shouldMove) {
            isFirstLayoutComplete = true
            moveToRandomPosition(contentContainer)
        }
    }

    /**
     * Adjusts content scale based on time string width.
     */
    fun adjustContentScale(
        contentContainer: View,
        timeInWordsView: TextView,
        getTimeInWordsHtml: () -> String
    ): Float {
        val screenWidth = lastScreenWidth
        val horizontalMargin = fixedHorizontalMargin * 2
        val maxWidth = if (isLandscape) {
            (screenWidth / 2) - horizontalMargin
        } else {
            screenWidth - horizontalMargin
        }

        if (maxWidth <= 0) return 1.0f

        val timeSpanned = HtmlCompat.fromHtml(getTimeInWordsHtml(), HtmlCompat.FROM_HTML_MODE_LEGACY)
        val tempTextView = TextView(context)
        tempTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, timeInWordsView.textSize)
        tempTextView.typeface = timeInWordsView.typeface
        tempTextView.text = timeSpanned

        val widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST)
        val heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        tempTextView.measure(widthMeasureSpec, heightMeasureSpec)

        var naturalWidth = tempTextView.measuredWidth.toFloat()
        if (naturalWidth <= 0) {
            naturalWidth = maxWidth.toFloat()
        }

        val scaleFactor = (maxWidth * 0.98f) / naturalWidth
        contentContainer.scaleX = scaleFactor
        contentContainer.scaleY = scaleFactor
        return scaleFactor
    }

    /**
     * Animates content scale change.
     */
    fun animateScaleChange(contentContainer: View, scaleFactor: Float) {
        contentContainer.animate()
            .scaleX(scaleFactor)
            .scaleY(scaleFactor)
            .setDuration(300L)
            .start()
    }

    /**
     * Moves content container to random vertical position.
     */
    fun moveToRandomPosition(contentContainer: View) {
        if (!isFirstLayoutComplete) return
        val params = contentContainer.layoutParams as RelativeLayout.LayoutParams
        params.leftMargin = fixedHorizontalMargin
        params.topMargin = if (maxVerticalMargin > 0) Random.nextInt(0, maxVerticalMargin) else 0
        contentContainer.layoutParams = params
    }

    fun isFirstLayoutComplete() = isFirstLayoutComplete
    fun setFirstLayoutComplete(complete: Boolean) { isFirstLayoutComplete = complete }
    fun getMaxVerticalMargin() = maxVerticalMargin
    fun isLandscape() = isLandscape
    fun getLastScreenWidth() = lastScreenWidth
    fun getLastScreenHeight() = lastScreenHeight
    }