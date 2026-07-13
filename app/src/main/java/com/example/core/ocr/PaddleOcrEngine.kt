package com.example.core.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device OCR via PaddleOCR's PP-OCRv4 mobile models (detection -> orientation classification
 * -> recognition), run through ONNX Runtime with OpenCV for the geometric operations (contour
 * extraction, rotated-rect fitting, perspective crop) that PaddleOCR's own reference pipeline
 * uses. Pre/post-processing parameters (resize/normalize constants, binarization threshold,
 * unclip ratio, CTC blank convention) are taken from RapidOCR's published reference config and
 * source, not guessed - RapidOCR is the standard ONNX Runtime port of these exact PaddleOCR models.
 *
 * Replaces ML Kit: ML Kit's Latin recognizer struggled badly on this corpus's harder documents
 * (dense scanned textbook pages, handwritten notes, low-quality photocopies) while doing fine on
 * clean typed documents. PP-OCRv4 is specifically trained for robustness on real-world photographed
 * text rather than just clean scans.
 */
class PaddleOcrEngine(private val context: Context) : OcrEngine {

    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var detSession: OrtSession? = null
    private var clsSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var charDict: List<String> = emptyList()

    override suspend fun extractText(images: List<Bitmap>): OcrResult = withContext(Dispatchers.Default) {
        if (images.isEmpty()) return@withContext OcrResult("", 1.0f, emptyList())
        if (!ensureInitialized()) return@withContext OcrResult("", 0.0f, images.map { "" })

        val pagesText = images.map { bitmap ->
            try {
                recognizePage(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to OCR a page", e)
                ""
            }
        }
        val combinedText = pagesText.joinToString("\n\n")
        val confidence = if (pagesText.any { it.isNotBlank() }) 1.0f else 0.0f
        OcrResult(text = combinedText, confidence = confidence, pagesText = pagesText)
    }

    private fun ensureInitialized(): Boolean {
        return synchronized(initializationLock) {
            if (detSession != null && clsSession != null && recSession != null) return@synchronized true
            try {
                if (!OpenCVLoader.initLocal()) {
                    Log.e(TAG, "OpenCV native library failed to load")
                    return false
                }
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                }
                detSession = ortEnvironment.createSession(
                    context.assets.open("$ASSET_DIR/det.onnx").use { it.readBytes() }, sessionOptions
                )
                clsSession = ortEnvironment.createSession(
                    context.assets.open("$ASSET_DIR/cls.onnx").use { it.readBytes() }, sessionOptions
                )
                recSession = ortEnvironment.createSession(
                    context.assets.open("$ASSET_DIR/rec.onnx").use { it.readBytes() }, sessionOptions
                )
                charDict = context.assets.open("$ASSET_DIR/dict.txt").bufferedReader().readLines()
                Log.d(TAG, "PaddleOcrEngine initialized (dict size=${charDict.size})")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                detSession?.close(); clsSession?.close(); recSession?.close()
                detSession = null; clsSession = null; recSession = null
                false
            }
        }
    }

    // ============================== Page pipeline ==============================

    private fun recognizePage(bitmap: Bitmap): String {
        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)
        val srcRgb = Mat()
        Imgproc.cvtColor(srcMat, srcRgb, Imgproc.COLOR_RGBA2RGB)
        srcMat.release()

        val boxes = detectTextBoxes(srcRgb)
        val orderedBoxes = sortReadingOrder(boxes)

        val lines = orderedBoxes.mapNotNull { box ->
            try {
                var crop = cropPerspective(srcRgb, box)
                crop = applyOrientation(crop)
                val text = recognizeLine(crop)
                crop.release()
                text.ifBlank { null }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to process one detected box", e)
                null
            }
        }
        srcRgb.release()
        return lines.joinToString("\n")
    }

    // ============================== Detection (DBNet) ==============================

    private data class DetBox(val points: Array<Point>)

    private fun detectTextBoxes(srcRgb: Mat): List<DetBox> {
        val srcH = srcRgb.rows()
        val srcW = srcRgb.cols()

        // Resize: limit_type="min" - if the smaller side is under 736, scale it up to 736;
        // otherwise leave as-is, then round both dims to a multiple of 32 (network requirement).
        // Real-world phone photos are usually already well above 736 on both sides, so this
        // mostly just rounds to /32; MAX_PRACTICAL_SIDE guards against feeding an enormous
        // full-resolution photo straight into the detector on a phone CPU.
        var ratio = 1.0
        if (min(srcH, srcW) < DET_LIMIT_SIDE) {
            ratio = if (srcH < srcW) DET_LIMIT_SIDE.toDouble() / srcH else DET_LIMIT_SIDE.toDouble() / srcW
        }
        var resizeH = (srcH * ratio).roundToInt()
        var resizeW = (srcW * ratio).roundToInt()
        val longSide = max(resizeH, resizeW)
        if (longSide > MAX_PRACTICAL_SIDE) {
            val down = MAX_PRACTICAL_SIDE.toDouble() / longSide
            ratio *= down
            resizeH = (srcH * ratio).roundToInt()
            resizeW = (srcW * ratio).roundToInt()
        }
        resizeH = (resizeH / 32.0).roundToInt().coerceAtLeast(1) * 32
        resizeW = (resizeW / 32.0).roundToInt().coerceAtLeast(1) * 32

        val resized = Mat()
        Imgproc.resize(srcRgb, resized, Size(resizeW.toDouble(), resizeH.toDouble()))

        val inputTensorData = matToChwNormalizedFloatArray(resized)
        val actualH = resized.rows()
        val actualW = resized.cols()
        resized.release()

        val shape = longArrayOf(1, 3, actualH.toLong(), actualW.toLong())
        val probMap: Mat
        val session = detSession ?: throw IllegalStateException("det session not initialized")
        OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(inputTensorData), shape).use { inputTensor ->
            session.run(mapOf("x" to inputTensor)).use { result ->
                val output = result.get(0) as OnnxTensor
                val fb = output.floatBuffer
                probMap = Mat(actualH, actualW, org.opencv.core.CvType.CV_32F)
                val row = FloatArray(actualW)
                for (y in 0 until actualH) {
                    fb.position(y * actualW)
                    fb.get(row, 0, actualW)
                    probMap.put(y, 0, row)
                }
            }
        }

        // Binarize at thresh=0.3, then a small dilation to connect nearby text-region pixels.
        val binary = Mat()
        Imgproc.threshold(probMap, binary, DET_THRESH, 255.0, Imgproc.THRESH_BINARY)
        val binary8u = Mat()
        binary.convertTo(binary8u, org.opencv.core.CvType.CV_8U)
        binary.release()
        val dilated = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.dilate(binary8u, dilated, kernel)
        binary8u.release()

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        dilated.release()

        val boxes = mutableListOf<DetBox>()
        for (contour in contours) {
            if (contour.rows() < 3) continue
            val contour2f = MatOfPoint2f(*contour.toArray())
            val rotRect = Imgproc.minAreaRect(contour2f)
            contour2f.release()

            if (min(rotRect.size.width, rotRect.size.height) < DET_MIN_BOX_SIZE) continue

            val boundingRect = Imgproc.boundingRect(contour)
            val score = boxScore(probMap, boundingRect)
            if (score < DET_BOX_THRESH) continue

            val expanded = unclipRotatedRect(rotRect, DET_UNCLIP_RATIO)
            val srcPoints = rotatedRectPoints(expanded).map { p ->
                Point(p.x / ratio, p.y / ratio)
            }.toTypedArray()

            val width = distance(srcPoints[0], srcPoints[1])
            val height = distance(srcPoints[1], srcPoints[2])
            if (min(width, height) < DET_MIN_BOX_SIZE) continue

            boxes.add(DetBox(srcPoints))
        }
        probMap.release()
        return boxes
    }

    private fun boxScore(probMap: Mat, rect: Rect): Double {
        val clamped = Rect(
            rect.x.coerceIn(0, probMap.cols() - 1),
            rect.y.coerceIn(0, probMap.rows() - 1),
            rect.width.coerceAtMost(probMap.cols() - rect.x.coerceIn(0, probMap.cols() - 1)).coerceAtLeast(1),
            rect.height.coerceAtMost(probMap.rows() - rect.y.coerceIn(0, probMap.rows() - 1)).coerceAtLeast(1)
        )
        val roi = probMap.submat(clamped)
        val mean = Core.mean(roi).`val`[0]
        roi.release()
        return mean
    }

    /** Rectangle-specific polygon offset: offsetting a rectangle uniformly by `distance` on every
     * edge is exactly growing its width/height by 2*distance around the same center - no general
     * polygon-clipping library needed since minAreaRect always yields a rectangle, not an
     * arbitrary polygon. */
    private fun unclipRotatedRect(rect: RotatedRect, unclipRatio: Double): RotatedRect {
        val area = rect.size.width * rect.size.height
        val perimeter = 2 * (rect.size.width + rect.size.height)
        if (perimeter <= 0.0) return rect
        val distance = area * unclipRatio / perimeter
        val newSize = Size(rect.size.width + 2 * distance, rect.size.height + 2 * distance)
        return RotatedRect(rect.center, newSize, rect.angle)
    }

    private fun rotatedRectPoints(rect: RotatedRect): Array<Point> {
        val pts = arrayOfNulls<Point>(4)
        rect.points(pts)
        @Suppress("UNCHECKED_CAST")
        return pts as Array<Point>
    }

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // ============================== Crop + orientation ==============================

    private fun cropPerspective(srcRgb: Mat, box: DetBox): Mat {
        val pts = box.points
        val width = max(distance(pts[0], pts[1]), distance(pts[2], pts[3])).roundToInt().coerceAtLeast(1)
        val height = max(distance(pts[1], pts[2]), distance(pts[3], pts[0])).roundToInt().coerceAtLeast(1)

        val srcTri = MatOfPoint2f(pts[0], pts[1], pts[2], pts[3])
        val dstTri = MatOfPoint2f(
            Point(0.0, 0.0), Point(width.toDouble(), 0.0),
            Point(width.toDouble(), height.toDouble()), Point(0.0, height.toDouble())
        )
        val transform = Imgproc.getPerspectiveTransform(srcTri, dstTri)
        val cropped = Mat()
        Imgproc.warpPerspective(srcRgb, cropped, transform, Size(width.toDouble(), height.toDouble()))
        srcTri.release(); dstTri.release(); transform.release()

        // Vertical text lines (tall narrow crops) get rotated upright, matching PaddleOCR's
        // convention of rotating any crop whose height is at least 1.5x its width.
        return if (height.toDouble() / width.toDouble() >= 1.5) {
            val rotated = Mat()
            Core.rotate(cropped, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            cropped.release()
            rotated
        } else {
            cropped
        }
    }

    private fun applyOrientation(crop: Mat): Mat {
        val session = clsSession ?: return crop
        val resized = Mat()
        Imgproc.resize(crop, resized, Size(CLS_WIDTH.toDouble(), CLS_HEIGHT.toDouble()))
        val data = matToChwNormalizedFloatArray(resized)
        resized.release()

        val shape = longArrayOf(1, 3, CLS_HEIGHT.toLong(), CLS_WIDTH.toLong())
        OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(data), shape).use { inputTensor ->
            session.run(mapOf("x" to inputTensor)).use { result ->
                val output = result.get(0) as OnnxTensor
                val fb = output.floatBuffer
                val score0 = fb.get(0)
                val score1 = fb.get(1)
                if (score1 > score0 && score1 >= CLS_THRESH) {
                    val rotated = Mat()
                    Core.rotate(crop, rotated, Core.ROTATE_180)
                    crop.release()
                    return rotated
                }
            }
        }
        return crop
    }

    // ============================== Recognition (CRNN + CTC) ==============================

    private fun recognizeLine(crop: Mat): String {
        val session = recSession ?: return ""
        val origH = crop.rows()
        val origW = crop.cols()
        if (origH <= 0 || origW <= 0) return ""

        val targetW = (REC_HEIGHT.toDouble() * origW / origH).roundToInt().coerceAtLeast(1)
        val resized = Mat()
        Imgproc.resize(crop, resized, Size(targetW.toDouble(), REC_HEIGHT.toDouble()))
        val data = matToChwNormalizedFloatArray(resized)
        resized.release()

        val shape = longArrayOf(1, 3, REC_HEIGHT.toLong(), targetW.toLong())
        OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(data), shape).use { inputTensor ->
            session.run(mapOf("x" to inputTensor)).use { result ->
                val output = result.get(0) as OnnxTensor
                val outShape = output.info.shape // [1, T, numClasses]
                val timeSteps = outShape[1].toInt()
                val numClasses = outShape[2].toInt()
                val fb = output.floatBuffer

                val sb = StringBuilder()
                var lastIndex = -1
                for (t in 0 until timeSteps) {
                    val base = t * numClasses
                    var bestIdx = 0
                    var bestVal = fb.get(base)
                    for (c in 1 until numClasses) {
                        val v = fb.get(base + c)
                        if (v > bestVal) { bestVal = v; bestIdx = c }
                    }
                    // Index 0 is the CTC blank. dict.txt has exactly 438 lines (wc -l undercounted
                    // it as 437 - the last line has no trailing newline), matching the rec model's
                    // 438 output classes 1:1, so class index maps directly into charDict with no
                    // offset - charDict[0]="#" sits at the blank's position and is simply never
                    // printed since bestIdx==0 is skipped.
                    if (bestIdx != 0 && bestIdx != lastIndex) {
                        if (bestIdx in charDict.indices) sb.append(charDict[bestIdx])
                    }
                    lastIndex = bestIdx
                }
                return sb.toString()
            }
        }
    }

    // ============================== Shared preprocessing ==============================

    /** Standard PaddleOCR normalization across det/cls/rec: (pixel/255 - 0.5) / 0.5, per channel,
     * with output laid out as planar CHW float32 (matching every one of these models' input). */
    private fun matToChwNormalizedFloatArray(mat: Mat): FloatArray {
        val h = mat.rows()
        val w = mat.cols()
        val channels = 3
        val out = FloatArray(channels * h * w)
        val pixel = ByteArray(channels)
        val planeSize = h * w
        for (y in 0 until h) {
            for (x in 0 until w) {
                mat.get(y, x, pixel)
                val idx = y * w + x
                for (c in 0 until channels) {
                    val value = (pixel[c].toInt() and 0xFF) / 255.0f
                    out[c * planeSize + idx] = (value - NORM_MEAN) / NORM_STD
                }
            }
        }
        return out
    }

    private fun sortReadingOrder(boxes: List<DetBox>): List<DetBox> {
        if (boxes.isEmpty()) return boxes
        val withCenters = boxes.map { box ->
            val cy = box.points.sumOf { it.y } / 4.0
            val cx = box.points.sumOf { it.x } / 4.0
            val heights = listOf(distance(box.points[0], box.points[3]), distance(box.points[1], box.points[2]))
            Triple(box, cy, cx) to (heights.average())
        }
        val avgHeight = withCenters.map { it.second }.average().coerceAtLeast(1.0)
        return withCenters.sortedWith(
            compareBy(
                { (Math.round(it.first.second / (avgHeight * 0.6))) },
                { it.first.third }
            )
        ).map { it.first.first }
    }

    companion object {
        private const val TAG = "PaddleOcrEngine"
        private const val ASSET_DIR = "paddleocr"
        private val initializationLock = Any()

        private const val NORM_MEAN = 0.5f
        private const val NORM_STD = 0.5f

        // Detection (DB) - values verified against RapidOCR's published reference config, the
        // standard ONNX Runtime port of these exact PP-OCRv4 mobile models.
        private const val DET_LIMIT_SIDE = 736
        private const val MAX_PRACTICAL_SIDE = 1600 // safety cap for large phone-camera photos on mobile CPU
        private const val DET_THRESH = 0.3 // probMap is a 0..1 raw sigmoid output, not 0..255
        private const val DET_BOX_THRESH = 0.5
        private const val DET_UNCLIP_RATIO = 1.6
        private const val DET_MIN_BOX_SIZE = 3.0

        // Orientation classifier
        private const val CLS_WIDTH = 192
        private const val CLS_HEIGHT = 48
        private const val CLS_THRESH = 0.9

        // Recognition
        private const val REC_HEIGHT = 48
    }
}
