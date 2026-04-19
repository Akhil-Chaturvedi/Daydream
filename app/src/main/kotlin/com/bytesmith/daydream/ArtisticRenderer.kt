package com.bytesmith.daydream

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * Handles artistic rendering of images in different sketch styles.
 * 
 * NOW USES GPUImage instead of OpenCV for ~15MB APK size reduction.
 * Pipeline: Grayscale → Gaussian Blur → Sobel Edge Detection → Invert
 * 
 * OpenCV code has been commented out but preserved for reference.
 * Can be fully removed after build verification succeeds.
 */
object ArtisticRenderer {

    private const val TAG = "ArtisticRenderer"

    // Style flags - kept for future use if needed
    private const val ENABLE_COLOR_OUTLINES = false
    private const val ENABLE_PENCIL_SKETCH = false
    private const val USE_HISTOGRAM_EQUALIZATION = true

    // Constants for inverted sketch adaptive threshold
    private const val INVERTED_ADAPTIVE_BLOCK_SIZE = 17
    private const val INVERTED_ADAPTIVE_C_CONSTANT = 4.0

    // Kuwahara filter kernel size
    private const val KUWAHARA_KERNEL_SIZE = 5

    // Canny edge detection thresholds
    private const val CANNY_MIN_LOWER_THRESHOLD = 30.0
    private const val CANNY_MAX_UPPER_THRESHOLD = 200.0
    private const val CANNY_LOWER_THRESHOLD_RATIO = 0.66
    private const val CANNY_UPPER_THRESHOLD_RATIO = 1.33

    // Morphological operations kernel sizes
    private const val DILATE_KERNEL_SIZE = 2.0

    // Pencil sketch parameters
    private const val GAUSSIAN_BLUR_KERNEL_SIZE = 25.0
    private const val PENCIL_SKETCH_DIVIDE_SCALE = 256.0

    /**
     * Creates an artistic canvas from the source bitmap.
     * This is the main entry point, replacing the CanvasGenerator facade.
     * 
     * NOW USES GPUImage instead of OpenCV for ~15MB APK size reduction.
     *
     * @param context Application context (needed for GPUImage initialization)
     * @param source Source bitmap to process
     * @return Processed bitmap or null if processing fails
     */
    fun create(context: Context, source: Bitmap): Bitmap? {
        // Use GPUImage-based wireframe processor (replaces OpenCV pipeline)
        return WireframeProcessor.createWireframe(context, source)
    }

    /*
    // =========================================================================
    // OPENCV-DEPENDENT CODE COMMENTED OUT - Using GPUImage instead
    // Can be deleted after build verification succeeds
    // =========================================================================

    import org.opencv.core.*
    import org.opencv.imgproc.Imgproc
    import org.opencv.android.Utils

    /**
     * Creates an outline sketch from the source bitmap.
     * OPENCV VERSION - DEPRECATED
     */
    fun createOutlineSketch(context: Context, source: Bitmap): Bitmap? {
        val allMats = mutableListOf<Mat>()
        val contours = ArrayList<MatOfPoint>()

        try {
            // 1. INPUT PREPARATION
            val srcMat = Mat()
            allMats.add(srcMat)
            Utils.bitmapToMat(source, srcMat)
            Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_BGRA2BGR)

            val grayMat = Mat()
            allMats.add(grayMat)
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_BGR2GRAY)

            if (USE_HISTOGRAM_EQUALIZATION) {
                Imgproc.equalizeHist(grayMat, grayMat)
            }

            // 2. SUBJECT MASKING
            val faceRects = FaceDetector.detectFaces(context, grayMat)
            val subjectMask = ContourProcessor.createSubjectMask(srcMat, faceRects)
            allMats.add(subjectMask)

            // 3. ARTISTIC SMOOTHING
            val smoothedMat = Mat()
            allMats.add(smoothedMat)
            ContourProcessor.kuwaharaFilter(grayMat, smoothedMat, KUWAHARA_KERNEL_SIZE)

            if (ENABLE_PENCIL_SKETCH) {
                return createPencilSketchResult(smoothedMat, allMats)
            }

            // Standard Contour-Based Pipeline
            return createContourBasedResult(
                srcMat, grayMat, smoothedMat, subjectMask,
                contours, faceRects, allMats
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error during outline sketch generation", e)
            return null
        } finally {
            allMats.forEach { it.release() }
            contours.forEach { it.release() }
            Log.d(TAG, "All Mats released for outline sketch.")
        }
    }

    private fun createPencilSketchResult(smoothedMat: Mat, allMats: MutableList<Mat>): Bitmap {
        val pencilMat = createPencilSketch(smoothedMat)
        allMats.add(pencilMat)

        val canvasMat = Mat()
        allMats.add(canvasMat)
        Imgproc.cvtColor(pencilMat, canvasMat, Imgproc.COLOR_GRAY2BGR)

        val resultBitmap = Bitmap.createBitmap(canvasMat.cols(), canvasMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(canvasMat, resultBitmap)
        return resultBitmap
    }

    private fun createContourBasedResult(
        srcMat: Mat,
        grayMat: Mat,
        @Suppress("UNUSED_PARAMETER") smoothedMat: Mat,
        subjectMask: Mat,
        contours: ArrayList<MatOfPoint>,
        faceRects: List<Rect>,
        allMats: MutableList<Mat>
    ): Bitmap {
        // 4. HYBRID EDGE DETECTION
        val edgesMat = ContourProcessor.detectEdges(grayMat, subjectMask)
        allMats.add(edgesMat)

        // 5. FIND AND FILTER CONTOURS
        val (foundContours, hierarchy) = ContourProcessor.findContours(edgesMat)
        contours.addAll(foundContours)
        allMats.add(hierarchy)

        // 6. SETUP CANVAS AND DRAW
        val canvasMat = if (ENABLE_COLOR_OUTLINES) {
            Mat.zeros(srcMat.size(), CvType.CV_8UC3)
        } else {
            Mat.zeros(srcMat.size(), CvType.CV_8UC1)
        }
        allMats.add(canvasMat)

        val sobelMat = ContourProcessor.calculateSobelMagnitude(grayMat)
        allMats.add(sobelMat)

        val imageDiagonal = sqrt((srcMat.width() * srcMat.width() + srcMat.height() * srcMat.height()).toDouble())
        val minContourLength = imageDiagonal * 0.01

        var drawnContours = 0
        contours.forEachIndexed { index, contour ->
            if (ContourProcessor.shouldDrawContour(contour, index, hierarchy, faceRects, minContourLength)) {
                val center = ContourProcessor.calculateContourCenter(contour)
                val isFaceContour = faceRects.any { it.contains(center) }

                val simplifiedContour = ContourProcessor.simplifyContour(contour, isFaceContour)
                val thickness = ContourProcessor.calculateThickness(simplifiedContour, sobelMat, isFaceContour)

                val color = if (ENABLE_COLOR_OUTLINES) {
                    ContourProcessor.calculateAverageColor(simplifiedContour, srcMat)
                } else {
                    Scalar(255.0, 255.0, 255.0)
                }

                Imgproc.polylines(canvasMat, listOf(simplifiedContour), false, color, thickness, Imgproc.LINE_AA)
                drawnContours++
                simplifiedContour.release()
            }
        }
        Log.d(TAG, "Contours drawn: $drawnContours of ${contours.size}")

        // 7. FINAL CONVERSION
        val resultBitmap = Bitmap.createBitmap(canvasMat.cols(), canvasMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(canvasMat, resultBitmap)
        return resultBitmap
    }

    /**
     * Creates an artistic sketch by inverting the main subject and overlaying Canny edges.
     * OPENCV VERSION - DEPRECATED
     */
    fun createInvertedSketch(source: Bitmap): Bitmap? {
        val allMats = mutableListOf<Mat>()

        try {
            // 1. Basic Setup
            val srcMat = Mat()
            allMats.add(srcMat)
            Utils.bitmapToMat(source, srcMat)
            Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_BGRA2BGR)

            val grayMat = Mat()
            allMats.add(grayMat)
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_BGR2GRAY)

            // ... (rest of OpenCV code)

        } catch (e: Exception) {
            Log.e(TAG, "Error during inverted sketch generation", e)
            return null
        } finally {
            allMats.forEach { it.release() }
        }
        return null
    }

    private fun createPencilSketch(grayMat: Mat): Mat {
        val blurred = Mat()
        allMats.add(blurred)
        Imgproc.GaussianBlur(grayMat, blurred, Size(GAUSSIAN_BLUR_KERNEL_SIZE, GAUSSIAN_BLUR_KERNEL_SIZE), 0.0)

        val sketch = Mat()
        allMats.add(sketch)
        Imgproc.divide(grayMat, blurred, sketch, PENCIL_SKETCH_DIVIDE_SCALE)
        return sketch
    }

    // END OF OPENCV-DEPENDENT CODE
    // =========================================================================
    */
}