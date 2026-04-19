package com.bytesmith.daydream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import kotlinx.coroutines.*
import kotlin.math.exp

/**
 * Handles all animation logic for the DreamService.
 * Extracted from DreamService for better separation of concerns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimationController(
    private val context: android.content.Context,
    private val scope: CoroutineScope
) {
    val transitionOutDurationMs: Long
        get() = DaydreamSettings.Animation.getTransitionOutMs(context)
    
    val transitionInDurationMs: Long
        get() = DaydreamSettings.Animation.getTransitionInMs(context)

    private val breathingCurve = BreathingCurve()

    /**
     * Breathing curve for smooth fade animations.
     */
    private inner class BreathingCurve {
        private val fadeOutK: Float 
            get() = DaydreamSettings.Animation.getFadeOutK(context)
        private val fadeInK: Float 
            get() = DaydreamSettings.Animation.getFadeInK(context)

        fun getFadeOutAlpha(normalizedTime: Float): Float {
            val t = normalizedTime.coerceIn(0f, 1f)
            return exp(-fadeOutK * t * t).toFloat()
        }

        fun getFadeInAlpha(normalizedTime: Float): Float {
            val t = normalizedTime.coerceIn(0f, 1f)
            return exp(-fadeInK * (1.0f - t) * (1.0f - t)).toFloat()
        }
    }

    /**
     * Animates fade out of media elements.
     */
    suspend fun animateMediaFadeOut(
        songNameView: View,
        canvasArtView: View
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val animators = mutableListOf<Animator>()

        val textAnim = ValueAnimator.ofFloat(songNameView.alpha, 0f)
        textAnim.addUpdateListener { songNameView.alpha = it.animatedValue as Float }
        animators.add(textAnim)

        if (canvasArtView.visibility == View.VISIBLE) {
            val artAnim = ValueAnimator.ofFloat(canvasArtView.alpha, 0f)
            artAnim.addUpdateListener { canvasArtView.alpha = it.animatedValue as Float }
            animators.add(artAnim)
        }

        if (animators.isEmpty()) {
            cont.resume(Unit) {}
            return@suspendCancellableCoroutine
        }

        animators[0].apply {
            duration = transitionOutDurationMs
            interpolator = LinearInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cont.resume(Unit) {}
                }
            })
            start()
        }
        for (i in 1 until animators.size) {
            animators[i].duration = transitionOutDurationMs
            animators[i].interpolator = LinearInterpolator()
            animators[i].start()
        }
    }

    /**
     * Animates fade in of media elements using breathing curve.
     */
    suspend fun animateMediaFadeIn(
        songNameView: View,
        canvasArtView: View
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val animators = mutableListOf<Animator>()

        val textAnim = ValueAnimator.ofFloat(0f, 1f)
        textAnim.addUpdateListener { 
            val t = it.animatedValue as Float
            songNameView.alpha = breathingCurve.getFadeInAlpha(t)
        }
        animators.add(textAnim)

        if (canvasArtView.visibility == View.VISIBLE) {
            val artAnim = ValueAnimator.ofFloat(0f, 1f)
            artAnim.addUpdateListener { 
                val t = it.animatedValue as Float
                canvasArtView.alpha = breathingCurve.getFadeInAlpha(t)
            }
            animators.add(artAnim)
        }

        if (animators.isEmpty()) {
            cont.resume(Unit) {}
            return@suspendCancellableCoroutine
        }

        animators[0].apply {
            duration = transitionInDurationMs
            interpolator = LinearInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cont.resume(Unit) {}
                }
            })
            start()
        }
        for (i in 1 until animators.size) {
            animators[i].duration = transitionInDurationMs
            animators[i].interpolator = LinearInterpolator()
            animators[i].start()
        }
    }

    /**
     * Animates alpha transition for a view using breathing curve.
     * Uses ValueAnimator instead of busy-wait loop for proper animation timing.
     */
    suspend fun animateAlphaManual(view: View, isFadeOut: Boolean) {
        suspendCancellableCoroutine<Unit> { cont ->
            val animator = ValueAnimator.ofFloat(0f, 1f)
            animator.duration = if (isFadeOut) transitionOutDurationMs else transitionInDurationMs
            animator.interpolator = LinearInterpolator()
            animator.addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                view.alpha = if (isFadeOut) {
                    breathingCurve.getFadeOutAlpha(t)
                } else {
                    breathingCurve.getFadeInAlpha(t)
                }
            }
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.alpha = if (isFadeOut) 0f else 1f
                    cont.resume(Unit) {}
                }
            })
            cont.invokeOnCancellation { animator.cancel() }
            animator.start()
        }
    }
}