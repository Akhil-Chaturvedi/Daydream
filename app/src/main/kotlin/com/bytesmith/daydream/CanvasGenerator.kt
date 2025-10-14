package com.bytesmith.daydream

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier
import org.opencv.objdetect.Objdetect
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object CanvasGenerator {

    private const val TAG = "CanvasGenerator"

    // --- General Style ---
    /** Set to true to sample colors from the original image for the outlines. False for white lines. */
    private const val ENABLE_COLOR_OUTLINES = false
    /** The base thickness for drawn lines. */
    private const val MIN_LINE_THICKNESS = 2
    /** The maximum thickness for drawn lines (used on strong edges or faces). */
    private const val MAX_LINE_THICKNESS = 4
    /** Set to true to use pencil sketch mode (shaded effect) instead of line contours. */
    private const val ENABLE_PENCIL_SKETCH = false  // New: Toggle for alternative pencil mode

    // --- Preprocessing ---
    /** Kernel size for the Kuwahara filter. Larger values create a more "painterly" effect. Must be an odd number. */
    private const val KUWAHARA_KERNEL_SIZE = 5
    /** Set to true to apply histogram equalization. Good for low-contrast images. */
    private const val USE_HISTOGRAM_EQUALIZATION = true

    // --- Contour Filtering & Simplification ---
    /** Epsilon for approxPolyDP is arcLength * this ratio. Higher values = simpler, more angular lines. */
    private const val ADAPTIVE_APPROX_EPSILON_RATIO = 0.01  // Slightly increased from 0.009 for abstraction
    /** Face-specific epsilon ratio for finer details. */
    private const val FACE_APPROX_EPSILON_RATIO = 0.005  // New: Lower for face-aligned detail
    /** Contours shorter than this fraction of the image diagonal are discarded. */
    private const val MIN_CONTOUR_LENGTH_RATIO = 0.01  // Slightly increased to reduce noise
    /** Maximum contour hierarchy depth to draw. Prevents drawing noisy inner contours. 2 is a good value. */
    private const val HIERARCHY_MAX_LEVEL = 2

    // --- Face Detection ---
    private var frontalCascade: CascadeClassifier? = null
    private var profileCascade: CascadeClassifier? = null

    fun create(context: Context, source: Bitmap): Bitmap? {
        val allMats = mutableListOf<Mat>()
        val contours = ArrayList<MatOfPoint>()

        if (!org.opencv.android.OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV native library failed to load.")
            return null
        }

        try {
            loadCascades(context)

            // 1. INPUT PREPARATION
            val srcMat = Mat()
            allMats.add(srcMat)
            Utils.bitmapToMat(source, srcMat)
            Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_BGRA2BGR) // Ensure 3 channels

            val grayMat = Mat()
            allMats.add(grayMat)
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_BGR2GRAY)

            if (USE_HISTOGRAM_EQUALIZATION) {
                Imgproc.equalizeHist(grayMat, grayMat)
            }

            // 2. SUBJECT MASKING (Improved with GrabCut)
            val faceRects = detectAllFaces(grayMat)
            Log.d(TAG, "Faces detected: ${faceRects.size}")
            val subjectMask = createSubjectMaskWithGrabCut(srcMat, grayMat, faceRects)
            allMats.add(subjectMask)

            // 3. ARTISTIC SMOOTHING (The Kuwahara Filter)
            val smoothedMat = Mat()
            allMats.add(smoothedMat)
            kuwaharaFilter(grayMat, smoothedMat, KUWAHARA_KERNEL_SIZE)

            if (ENABLE_PENCIL_SKETCH) {
                // New: Alternative Pencil Sketch Mode (shaded effect)
                val pencilMat = createPencilSketch(smoothedMat)
                allMats.add(pencilMat)

                // Use pencilMat directly as canvas (grayscale to bitmap)
                val canvasMat = Mat()
                allMats.add(canvasMat)
                Imgproc.cvtColor(pencilMat, canvasMat, Imgproc.COLOR_GRAY2BGR) // For consistency

                val resultBitmap = Bitmap.createBitmap(canvasMat.cols(), canvasMat.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(canvasMat, resultBitmap)
                return resultBitmap
            } else {
                // Standard Contour-Based Pipeline

                // 4. HYBRID EDGE DETECTION
                val cannyMat = Mat()
                allMats.add(cannyMat)
                val median = calculateMedian(smoothedMat)
                val lowerThreshold = max(40.0, 0.66 * median)  // Raised min for less noise
                val upperThreshold = min(200.0, 1.33 * median)
                Imgproc.Canny(smoothedMat, cannyMat, lowerThreshold, upperThreshold, 3, true)

                val adaptiveMat = Mat()
                allMats.add(adaptiveMat)
                Imgproc.adaptiveThreshold(smoothedMat, adaptiveMat, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 11, 2.0)

                val edgesMat = Mat()
                allMats.add(edgesMat)
                Core.bitwise_or(cannyMat, adaptiveMat, edgesMat) // Combine the two edge maps
                Core.bitwise_and(edgesMat, subjectMask, edgesMat) // Keep only edges within the subject mask

                // New: Morphological Refinement (close gaps in edges)
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
                allMats.add(kernel)
                Imgproc.morphologyEx(edgesMat, edgesMat, Imgproc.MORPH_CLOSE, kernel)

                // 5. FIND AND FILTER CONTOURS
                val hierarchy = Mat()
                allMats.add(hierarchy)
                Imgproc.findContours(edgesMat, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
                Log.d(TAG, "Total contours found: ${contours.size}")

                // 6. SETUP CANVAS AND DRAW
                val canvasMat = if (ENABLE_COLOR_OUTLINES) Mat.zeros(srcMat.size(), CvType.CV_8UC3) else Mat.zeros(srcMat.size(), CvType.CV_8UC1)
                allMats.add(canvasMat)

                val sobelMat = Mat()
                allMats.add(sobelMat)
                calculateSobelMagnitude(grayMat, sobelMat)

                val imageDiagonal = sqrt((srcMat.width() * srcMat.width() + srcMat.height() * srcMat.height()).toDouble())
                val minContourLength = imageDiagonal * MIN_CONTOUR_LENGTH_RATIO

                var drawnContours = 0
                contours.forEachIndexed { index, contour ->
                    if (shouldDrawContour(contour, index, hierarchy, faceRects, minContourLength)) {
                        val contour2f = MatOfPoint2f(*contour.toArray())
                        val arcLength = Imgproc.arcLength(contour2f, true)

                        val approx2f = MatOfPoint2f()
                        val center = calculateContourCenter(contour)
                        val isFaceContour = faceRects.any { it.contains(center) }
                        val epsilonRatio = if (isFaceContour) FACE_APPROX_EPSILON_RATIO else ADAPTIVE_APPROX_EPSILON_RATIO
                        val epsilon = max(1.0, arcLength * epsilonRatio)
                        Imgproc.approxPolyDP(contour2f, approx2f, epsilon, true)
                        val approxContour = MatOfPoint(*approx2f.toArray())

                        val avgStrength = calculateAverageEdgeStrength(approxContour, sobelMat)
                        
                        val minThickness = if (isFaceContour) 1 else MIN_LINE_THICKNESS  // New: Thinner for faces
                        val maxThickness = if (isFaceContour) 3 else MAX_LINE_THICKNESS  // New: Limited range for faces
                        val thickness = ((avgStrength / 255.0) * (maxThickness - minThickness) + minThickness).toInt()

                        val color = if (ENABLE_COLOR_OUTLINES) {
                            calculateAverageColorAlongContour(approxContour, srcMat)
                        } else {
                            Scalar(255.0, 255.0, 255.0)
                        }

                        Imgproc.polylines(canvasMat, listOf(approxContour), false, color, thickness, Imgproc.LINE_AA)
                        
                        drawnContours++
                        contour2f.release()
                        approx2f.release()
                        approxContour.release()
                    }
                }
                Log.d(TAG, "Contours drawn: $drawnContours of ${contours.size}")
                
                // 7. FINAL CONVERSION
                val resultBitmap = Bitmap.createBitmap(canvasMat.cols(), canvasMat.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(canvasMat, resultBitmap)
                return resultBitmap
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during canvas generation", e)
            return null
        } finally {
            allMats.forEach { it.release() }
            contours.forEach { it.release() }
            Log.d(TAG, "All Mats released.")
        }
    }

    // Corrected: Custom Pencil Sketch Implementation
    private fun createPencilSketch(grayMat: Mat): Mat {
        val invGray = Mat()
        // FIX: Use bitwise_not for efficient grayscale image inversion (255 - pixel).
        Core.bitwise_not(grayMat, invGray)

        val blurInv = Mat()
        // Using a slightly larger kernel can create a softer sketch effect.
        Imgproc.GaussianBlur(invGray, blurInv, Size(25.0, 25.0), 0.0)

        val pencilSketch = Mat()
        // This technique is a form of "Color Dodge" blending to create the sketch.
        Core.divide(grayMat, blurInv, pencilSketch, 256.0)
    
        Log.d(TAG, "Pencil sketch mode applied.")

        invGray.release()
        blurInv.release()
        // The divisor matrix is blurInv itself, no need for an extra one.
        return pencilSketch
    }

    // Improved: Subject Mask with GrabCut
    private fun createSubjectMaskWithGrabCut(srcMat: Mat, grayMat: Mat, faceRects: List<Rect>): Mat {
        val mask = Mat.zeros(srcMat.size(), CvType.CV_8UC1)
        val bgdModel = Mat(1, 65, CvType.CV_64FC1, Scalar(0.0))
        val fgdModel = Mat(1, 65, CvType.CV_64FC1, Scalar(0.0))

        if (faceRects.isEmpty()) {
            // Default to central region
            val center = Point(mask.cols() / 2.0, mask.rows() / 2.0)
            val radius = min(mask.cols(), mask.rows()) / 3
            Imgproc.circle(mask, center, radius.toInt(), Scalar(Imgproc.GC_PR_FGD.toDouble()), -1)
        } else {
            faceRects.forEach { face ->
                val expanded = Rect(
                    max(0, face.x - face.width / 2),
                    max(0, face.y - face.height / 2),
                    min(mask.cols() - face.x + face.width / 2, face.width * 2),
                    min(mask.rows() - face.y + face.height / 2, face.height * 3)
                )
                Imgproc.rectangle(mask, expanded.tl(), expanded.br(), Scalar(Imgproc.GC_PR_FGD.toDouble()), -1)
            }
        }

        Imgproc.grabCut(srcMat, mask, Rect(), bgdModel, fgdModel, 5, Imgproc.GC_INIT_WITH_MASK)

        // Extract foreground mask (GC_FGD + GC_PR_FGD)
        val temp1 = Mat()
        val temp2 = Mat()
        val fgMask = Mat()
        Core.inRange(mask, Scalar(Imgproc.GC_FGD.toDouble()), Scalar(Imgproc.GC_FGD.toDouble()), temp1)
        Core.inRange(mask, Scalar(Imgproc.GC_PR_FGD.toDouble()), Scalar(Imgproc.GC_PR_FGD.toDouble()), temp2)
        Core.bitwise_or(temp1, temp2, fgMask)

        // Blur for smooth transitions
        Imgproc.GaussianBlur(fgMask, fgMask, Size(21.0, 21.0), 0.0)

        Log.d(TAG, "GrabCut subject mask created.")

        mask.release()
        bgdModel.release()
        fgdModel.release()
        temp1.release()
        temp2.release()
        return fgMask
    }

    private fun shouldDrawContour(contour: MatOfPoint, index: Int, hierarchy: Mat, faceRects: List<Rect>, minLength: Double): Boolean {
        // Filter 1: Hierarchy level
        val level = getContourLevel(index, hierarchy)
        if (level > HIERARCHY_MAX_LEVEL) return false

        // Filter 2: Arc Length
        val arcLength = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
        val center = calculateContourCenter(contour)
        val isFaceContour = faceRects.any { it.contains(center) }
        
        // Use a smaller min length for face contours to preserve details
        val effectiveMinLength = if (isFaceContour) minLength / 2.0 else minLength
        
        return arcLength > effectiveMinLength
    }

    private fun getContourLevel(index: Int, hierarchy: Mat): Int {
        var level = 0
        var current = index
        while (true) {
            val parent = hierarchy.get(0, current)[3].toInt()
            if (parent < 0) break
            level++
            current = parent
        }
        return level
    }

    private fun kuwaharaFilter(src: Mat, dst: Mat, kernelSize: Int) {
        val halfKernel = kernelSize / 2
        dst.create(src.size(), src.type())
        val padded = Mat()
        Core.copyMakeBorder(src, padded, halfKernel, halfKernel, halfKernel, halfKernel, Core.BORDER_REPLICATE)
        val srcData = ByteArray(padded.total().toInt() * padded.channels())
        padded.get(0, 0, srcData)
        val dstData = ByteArray(dst.total().toInt() * dst.channels())
        val width = dst.width()
        val height = dst.height()
        val paddedWidth = padded.width()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val means = DoubleArray(4)
                val stdDevs = DoubleArray(4)
                val rois = listOf(
                    Rect(x, y, halfKernel + 1, halfKernel + 1),
                    Rect(x + halfKernel, y, halfKernel + 1, halfKernel + 1),
                    Rect(x, y + halfKernel, halfKernel + 1, halfKernel + 1),
                    Rect(x + halfKernel, y + halfKernel, halfKernel + 1, halfKernel + 1)
                )

                for (i in rois.indices) {
                    val roi = rois[i]
                    var sum = 0.0
                    var sumSq = 0.0
                    for (ky in 0 until roi.height) {
                        for (kx in 0 until roi.width) {
                            val pixelVal = srcData[((roi.y + ky) * paddedWidth) + (roi.x + kx)].toUByte().toInt()
                            sum += pixelVal
                            sumSq += pixelVal * pixelVal
                        }
                    }
                    val numPixels = (roi.width * roi.height).toDouble()
                    val mean = sum / numPixels
                    means[i] = mean
                    stdDevs[i] = sqrt((sumSq / numPixels) - (mean * mean))
                }

                var minStdDevIndex = 0
                for (i in 1 until 4) {
                    if (stdDevs[i] < stdDevs[minStdDevIndex]) {
                        minStdDevIndex = i
                    }
                }
                dstData[(y * width) + x] = means[minStdDevIndex].toInt().toByte()
            }
        }
        dst.put(0, 0, dstData)
        padded.release()
    }

    private fun calculateSobelMagnitude(grayMat: Mat, sobelMat: Mat) {
        val sobelX = Mat()
        val sobelY = Mat()
        Imgproc.Sobel(grayMat, sobelX, CvType.CV_16S, 1, 0, 3)
        Imgproc.Sobel(grayMat, sobelY, CvType.CV_16S, 0, 1, 3)
        Core.magnitude(sobelX, sobelY, sobelMat)
        Core.normalize(sobelMat, sobelMat, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U)
        sobelX.release()
        sobelY.release()
    }

    private fun calculateAverageEdgeStrength(contour: MatOfPoint, sobelMat: Mat): Double {
        val points = contour.toArray()
        if (points.isEmpty()) return 0.0
        var sum = 0.0
        points.forEach { point ->
            val x = point.x.toInt().coerceIn(0, sobelMat.cols() - 1)
            val y = point.y.toInt().coerceIn(0, sobelMat.rows() - 1)
            sum += sobelMat.get(y, x)[0]
        }
        return sum / points.size
    }

    private fun calculateAverageColorAlongContour(contour: MatOfPoint, srcMat: Mat): Scalar {
        val points = contour.toArray()
        if (points.isEmpty()) return Scalar(255.0, 255.0, 255.0)
        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        points.forEach { point ->
            val x = point.x.toInt().coerceIn(0, srcMat.cols() - 1)
            val y = point.y.toInt().coerceIn(0, srcMat.rows() - 1)
            val pixel = srcMat.get(y, x)
            bSum += pixel[0]
            gSum += pixel[1]
            rSum += pixel[2]
        }
        return Scalar(bSum / points.size, gSum / points.size, rSum / points.size)
    }

    private fun detectAllFaces(grayMat: Mat): List<Rect> {
        val allFaces = mutableListOf<Rect>()
        frontalCascade?.let {
            val faces = MatOfRect()
            it.detectMultiScale(grayMat, faces, 1.1, 5, Objdetect.CASCADE_SCALE_IMAGE, Size(60.0, 60.0), Size())
            allFaces.addAll(faces.toList())
            faces.release()
        }
        profileCascade?.let {
            val faces = MatOfRect()
            it.detectMultiScale(grayMat, faces, 1.1, 5, Objdetect.CASCADE_SCALE_IMAGE, Size(60.0, 60.0), Size())
            allFaces.addAll(faces.toList())
            faces.release()
        }
        return allFaces
    }
    
    @Synchronized
    private fun loadCascades(context: Context) {
        if (frontalCascade != null && profileCascade != null) return
        Log.d(TAG, "Loading cascade files...")
        frontalCascade = loadCascade(context, R.raw.haarcascade_frontalface_alt, "haarcascade_frontalface_alt.xml")
        profileCascade = loadCascade(context, R.raw.haarcascade_profileface, "haarcascade_profileface.xml")
    }

    private fun loadCascade(context: Context, resourceId: Int, fileName: String): CascadeClassifier? {
        return try {
            val inputStream = context.resources.openRawResource(resourceId)
            val cascadeDir = context.getDir("cascade", Context.MODE_PRIVATE)
            val cascadeFile = File(cascadeDir, fileName)
            FileOutputStream(cascadeFile).use { os ->
                inputStream.copyTo(os)
            }
            inputStream.close()
            val cascade = CascadeClassifier(cascadeFile.absolutePath)
            if (cascade.empty()) {
                Log.e(TAG, "Failed to load cascade classifier from $fileName")
                null
            } else {
                cascade
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cascade file: $fileName", e)
            null
        }
    }

    private fun calculateContourCenter(contour: MatOfPoint): Point {
        val moments = Imgproc.moments(contour)
        return if (moments.m00 > 0) {
            Point(moments.m10 / moments.m00, moments.m01 / moments.m00)
        } else {
            if(contour.total() > 0) contour.toArray()[0] else Point(0.0, 0.0)
        }
    }

    private fun calculateMedian(mat: Mat): Double {
        val singleRowMat = mat.reshape(1, 1)
        val sortedMat = Mat()
        Core.sort(singleRowMat, sortedMat, Core.SORT_ASCENDING)
        val medianValue = if (sortedMat.cols() > 0) sortedMat.get(0, sortedMat.cols() / 2)[0] else 0.0
        singleRowMat.release()
        sortedMat.release()
        return medianValue
    }
}