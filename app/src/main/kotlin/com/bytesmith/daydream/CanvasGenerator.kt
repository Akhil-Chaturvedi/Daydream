// CanvasGenerator.kt

package com.bytesmith.daydream

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.core.MatOfPoint2f
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier
import org.opencv.objdetect.Objdetect
import org.opencv.saliency.Saliency
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.max

object CanvasGenerator {

    private const val TAG = "CanvasGenerator"

    // --- TWEAKABLE PARAMETERS ---
    private const val QUANTIZATION_LEVELS = 5
    private const val CONTOUR_MIN_LENGTH_DEFAULT = 50.0
    private const val CONTOUR_MIN_LENGTH_SALIENT = 20.0
    private const val MIN_LINE_THICKNESS = 1
    private const val MAX_LINE_THICKNESS = 2

    private var frontalCascade: CascadeClassifier? = null
    private var profileCascade: CascadeClassifier? = null

    fun create(context: Context, source: Bitmap): Bitmap? {
        val allMats = mutableListOf<Mat>()
        val contours = ArrayList<MatOfPoint>()

        // CRITICAL: Ensure OpenCV is loaded before any operation
        if (!org.opencv.android.OpenCVLoader.initLocal()) {
             // Fallback or specific asynchronous loading should be handled in the calling Activity/Service.
             Log.e(TAG, "OpenCV native library failed to load.")
             return null
        }

        try {
            loadCascades(context)

            val srcMat = Mat()
            allMats.add(srcMat)
            Utils.bitmapToMat(source, srcMat)

            // 1. Grayscale Conversion
            val grayMat = Mat()
            allMats.add(grayMat)
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_BGR2GRAY)

            val faceRects = detectAllFaces(grayMat)
            Log.d(TAG, "Total faces detected (frontal + profile): ${faceRects.size}")

            // 2. Saliency Map calculation
            val saliencyMap = Mat()
            allMats.add(saliencyMap)
            computeSaliencyMap(srcMat, saliencyMap)

            // 3. Threshold calculation
            val median = calculateMedian(grayMat)
            val lowerThreshold = max(0.0, 0.66 * median)
            val upperThreshold = min(255.0, 1.33 * median)

            // 4. Smoothing
            val smoothedMat = Mat()
            allMats.add(smoothedMat)
            Imgproc.bilateralFilter(grayMat, smoothedMat, 9, 75.0, 75.0)

            // 5. Quantization
            val quantizedMat = Mat()
            allMats.add(quantizedMat)
            quantizeImage(smoothedMat, quantizedMat)

            // 6. Edge Detection (Canny)
            val edgesMat = Mat()
            allMats.add(edgesMat)
            Imgproc.Canny(quantizedMat, edgesMat, lowerThreshold, upperThreshold)

            // 7. Find Contours
            val hierarchy = Mat()
            allMats.add(hierarchy)
            Imgproc.findContours(edgesMat, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)

            // 8. Prepare Canvas and Sobel Filter
            val canvasMat = Mat.zeros(srcMat.size(), CvType.CV_8UC1)
            allMats.add(canvasMat)

            val sobelMat = Mat()
            allMats.add(sobelMat)
            Imgproc.Sobel(grayMat, sobelMat, CvType.CV_16S, 1, 1)
            Core.convertScaleAbs(sobelMat, sobelMat)
            Core.normalize(sobelMat, sobelMat, 0.0, 255.0, Core.NORM_MINMAX)

            // 9. Process Contours
            for (contour in contours) {
                // We need MatOfPoint2f for arcLength calculation, this uses extra memory
                val contour2f = MatOfPoint2f()
                val contourMat = contour.clone() // Clone to avoid modifying the original list element during conversion
                allMats.add(contourMat)
                contourMat.convertTo(contour2f, CvType.CV_32F)
                allMats.add(contour2f)

                val center = calculateContourCenter(contour)
                val isFaceContour = faceRects.any { it.contains(center) }
                val contourSaliency = getSaliencyAtPoint(center, saliencyMap)

                val minLength = if (contourSaliency > 80) CONTOUR_MIN_LENGTH_SALIENT else CONTOUR_MIN_LENGTH_DEFAULT
                
                // Using contour2f for arcLength
                if (isFaceContour || Imgproc.arcLength(contour2f, true) > minLength) {
                    val points = contour.toArray()
                    for (point in points) {
                        // Check bounds before accessing pixel data
                        if (point.y.toInt() in 0 until sobelMat.rows() && point.x.toInt() in 0 until sobelMat.cols()) {
                            val edgeStrength = sobelMat.get(point.y.toInt(), point.x.toInt())[0]
                            val thickness = ((edgeStrength / 255.0) * (MAX_LINE_THICKNESS - MIN_LINE_THICKNESS) + MIN_LINE_THICKNESS).toInt()
                            Imgproc.circle(canvasMat, point, 1, Scalar(255.0), thickness)
                        }
                    }
                }
            }

            // 10. Final Conversion
            val resultBitmap = Bitmap.createBitmap(canvasMat.cols(), canvasMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(canvasMat, resultBitmap)
            return resultBitmap

        } catch (e: Exception) {
            Log.e(TAG, "An error occurred during canvas generation", e)
            return null
        } finally {
            // Release all collected Mats. This includes temporary MatOfPoint2f objects.
            allMats.forEach { it.release() }
            
            // Let's ensure the Mat objects in the 'contours' list are explicitly released as well
            contours.forEach { it.release() }

            Log.d(TAG, "All Mats released.")
        }
    }
    
    private fun detectAllFaces(grayMat: Mat): List<Rect> {
        val frontalFaces = MatOfRect()
        val profileFaces = MatOfRect()

        // Detects faces and stores them in the MatOfRect containers
        frontalCascade?.detectMultiScale(grayMat, frontalFaces, 1.1, 2, Objdetect.CASCADE_SCALE_IMAGE, Size(30.0, 30.0), Size())
        profileCascade?.detectMultiScale(grayMat, profileFaces, 1.1, 2, Objdetect.CASCADE_SCALE_IMAGE, Size(30.0, 30.0), Size())

        // Combine the results into a standard Kotlin List of Rects
        val allFaces = frontalFaces.toList() + profileFaces.toList()
        
        // Crucial: Release the MatOfRect containers immediately after extraction
        frontalFaces.release()
        profileFaces.release()
        
        return allFaces
    }

    @Synchronized
    private fun loadCascades(context: Context) {
        // Only load if they haven't been loaded before.
        if (frontalCascade != null && profileCascade != null) return

        Log.d(TAG, "Loading cascade files for the first time...")
        frontalCascade = loadCascade(context, R.raw.haarcascade_frontalface_alt, "haarcascade_frontalface_alt.xml")
        profileCascade = loadCascade(context, R.raw.haarcascade_profileface, "haarcascade_profileface.xml")
    }

    private fun loadCascade(context: Context, resourceId: Int, fileName: String): CascadeClassifier? {
        try {
            val inputStream = context.resources.openRawResource(resourceId)
            val cascadeDir = context.getDir("cascade", Context.MODE_PRIVATE)
            val cascadeFile = File(cascadeDir, fileName)
            FileOutputStream(cascadeFile).use { os ->
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    os.write(buffer, 0, bytesRead)
                }
            }
            inputStream.close()
            return CascadeClassifier(cascadeFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cascade file: $fileName", e)
            return null
        }
    }

    private fun computeSaliencyMap(srcMat: Mat, outputMap: Mat) {
        val smallMat = Mat() // Temporary Mat for processing
        Imgproc.resize(srcMat, smallMat, Size(128.0, 128.0))
        val saliency = org.opencv.saliency.StaticSaliencyFineGrained.create()        
        val tempSaliencyMap = Mat()
        saliency.computeSaliency(smallMat, tempSaliencyMap)
        Core.normalize(tempSaliencyMap, tempSaliencyMap, 0.0, 255.0, Core.NORM_MINMAX)
        Imgproc.resize(tempSaliencyMap, outputMap, srcMat.size())

        smallMat.release()
        tempSaliencyMap.release()
    }

    private fun getSaliencyAtPoint(point: Point, saliencyMap: Mat): Double {
        return if (point.y >= 0 && point.y < saliencyMap.rows() && point.x >= 0 && point.x < saliencyMap.cols()) {
            saliencyMap.get(point.y.toInt(), point.x.toInt())[0]
        } else {
            0.0
        }
    }

    private fun calculateContourCenter(contour: MatOfPoint): Point {
        val moments = Imgproc.moments(contour)
        return if (moments.m00 > 0) {
            Point(moments.m10 / moments.m00, moments.m01 / moments.m00)
        } else {
            Point(0.0, 0.0)
        }
    }
    
    private fun quantizeImage(inputMat: Mat, outputMat: Mat) {
        outputMat.create(inputMat.size(), inputMat.type())
        val step = 256.0 / QUANTIZATION_LEVELS.toDouble()

        val rows = inputMat.rows()
        val cols = inputMat.cols()
        
        // Assuming single channel CV_8UC1 input
        if (inputMat.channels() != 1 || inputMat.depth() != CvType.CV_8U) {
            Log.w(TAG, "Quantization input expected CV_8UC1 but got ${inputMat.type()}")
        }

        val data = ByteArray(rows * cols)
        inputMat.get(0, 0, data)

        for (i in data.indices) {
            val byteValue = data[i].toUByte().toInt() // Interpret as unsigned byte (0-255)
            
            // FIX for Issue 2: Convert Double result to Int first
            val doubleResult = Math.floor(byteValue / step) * step
            
            // Ensure result stays within [0, 255] and convert to Byte
            val quantizedInt = doubleResult.toInt().coerceIn(0, 255)
            
            // Convert to UByte, then safely back to signed Byte for Java interop
            data[i] = quantizedInt.toUByte().toByte()
        }

        outputMat.put(0, 0, data)
    }

    private fun calculateMedian(mat: Mat): Double {
        var singleChannel: Mat? = null
        var sorted: Mat? = null
        
        try {
            // Mat used in this function *must* be released here
            singleChannel = mat.reshape(1, 1)
            sorted = Mat()
            Core.sort(singleChannel, sorted, Core.SORT_ASCENDING)
            
            val medianIndex = sorted.cols() / 2
            // Must check if matrix is empty before accessing element
            if (sorted.cols() == 0) return 0.0
            
            return sorted.get(0, medianIndex)?.get(0) ?: 0.0

        } catch (e: Exception) {
            Log.e(TAG, "Error calculating median", e)
            return 0.0
        } finally {
            // Crucial: release local temporary Mats
            singleChannel?.release()
            sorted?.release()
        }
    }
}