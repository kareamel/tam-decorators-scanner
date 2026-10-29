package com.tamdecorators.scanner.util

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.sqrt

enum class ScanFilter { BW, COLOR, GRAY, PHOTO }

/**
 * Image processing for scanned documents, built on OpenCV.
 *
 * Note: Android bitmaps convert to OpenCV Mats in RGBA order, so every colour
 * conversion here uses the RGBA/RGB variants (OpenCV's own default is BGR).
 */
object ScanProcessor {
    // Edge detection
    private val BLUR_KERNEL = Size(5.0, 5.0)
    private const val CANNY_LOW_THRESHOLD = 60.0
    private const val CANNY_HIGH_THRESHOLD = 180.0

    /** How closely the simplified outline follows the contour, as a fraction of its perimeter. */
    private const val POLY_APPROX_EPSILON = 0.02
    private const val QUAD_CORNERS = 4L

    // Black & white filter
    private const val BILATERAL_DIAMETER = 7
    private const val BILATERAL_SIGMA = 50.0
    private const val THRESHOLD_BLOCK_SIZE = 31
    private const val THRESHOLD_OFFSET = 9.0

    // Colour filter (CLAHE = contrast-limited adaptive histogram equalisation)
    private const val CLAHE_CLIP_LIMIT = 2.5
    private val CLAHE_TILE_GRID = Size(8.0, 8.0)

    fun applyFilter(bitmap: Bitmap, filter: ScanFilter): Bitmap {
        return when (filter) {
            ScanFilter.PHOTO -> bitmap
            ScanFilter.GRAY -> toGray(bitmap)
            ScanFilter.COLOR -> enhanceColor(bitmap)
            ScanFilter.BW -> proBlackAndWhite(bitmap)
        }
    }

    /**
     * Straightens the document: maps the four corners in [quad]
     * (topLeft, topRight, bottomRight, bottomLeft as x,y pairs) onto a flat rectangle.
     * Returns the original bitmap if no corners were found.
     */
    fun perspectiveCrop(bitmap: Bitmap, quad: FloatArray?): Bitmap {
        if (quad == null || quad.size < 8) return bitmap

        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val srcPts = MatOfPoint2f(
            Point(quad[0].toDouble(), quad[1].toDouble()),
            Point(quad[2].toDouble(), quad[3].toDouble()),
            Point(quad[4].toDouble(), quad[5].toDouble()),
            Point(quad[6].toDouble(), quad[7].toDouble()),
        )

        // Output size: the longer of each pair of opposite edges.
        val topWidth = dist(quad[0], quad[1], quad[2], quad[3])
        val bottomWidth = dist(quad[6], quad[7], quad[4], quad[5])
        val outW = maxOf(topWidth, bottomWidth).toInt().coerceAtLeast(1)

        val leftHeight = dist(quad[0], quad[1], quad[6], quad[7])
        val rightHeight = dist(quad[2], quad[3], quad[4], quad[5])
        val outH = maxOf(leftHeight, rightHeight).toInt().coerceAtLeast(1)

        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW.toDouble(), 0.0),
            Point(outW.toDouble(), outH.toDouble()),
            Point(0.0, outH.toDouble()),
        )

        val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat(outH, outW, src.type())
        Imgproc.warpPerspective(src, out, transform, out.size(), Imgproc.INTER_CUBIC)

        val result = matToBitmap(out)
        src.release()
        srcPts.release()
        dstPts.release()
        transform.release()
        out.release()
        return result
    }

    /**
     * Finds the document in a greyscale frame: blur to remove noise, find edges,
     * then keep the largest convex outline that simplifies to exactly four corners.
     * Returns the corners ordered topLeft, topRight, bottomRight, bottomLeft, or null.
     */
    fun detectLargestQuad(grayMat: Mat): FloatArray? {
        val blur = Mat()
        Imgproc.GaussianBlur(grayMat, blur, BLUR_KERNEL, 0.0)
        val edges = Mat()
        Imgproc.Canny(blur, edges, CANNY_LOW_THRESHOLD, CANNY_HIGH_THRESHOLD)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        var best: Array<Point>? = null
        var bestArea = 0.0
        val approx = MatOfPoint2f()

        for (contour in contours) {
            val contour2f = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(contour2f, true)
            Imgproc.approxPolyDP(contour2f, approx, POLY_APPROX_EPSILON * perimeter, true)

            if (approx.total() == QUAD_CORNERS) {
                val corners = approx.toArray()
                val cornersMat = MatOfPoint(*corners)
                if (Imgproc.isContourConvex(cornersMat)) {
                    val area = abs(Imgproc.contourArea(cornersMat))
                    if (area > bestArea) {
                        bestArea = area
                        best = corners
                    }
                }
                cornersMat.release()
            }
            contour2f.release()
            contour.release()
        }
        blur.release()
        edges.release()
        hierarchy.release()
        approx.release()

        val ordered = best?.let { orderPoints(it) } ?: return null
        return floatArrayOf(
            ordered[0].x.toFloat(), ordered[0].y.toFloat(),
            ordered[1].x.toFloat(), ordered[1].y.toFloat(),
            ordered[2].x.toFloat(), ordered[2].y.toFloat(),
            ordered[3].x.toFloat(), ordered[3].y.toFloat(),
        )
    }

    /** Scanner-style black & white: smooth the paper texture, then threshold each area separately so shadows don't turn black. */
    private fun proBlackAndWhite(bitmap: Bitmap): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

        val denoise = Mat()
        Imgproc.bilateralFilter(gray, denoise, BILATERAL_DIAMETER, BILATERAL_SIGMA, BILATERAL_SIGMA)

        val bw = Mat()
        Imgproc.adaptiveThreshold(
            denoise,
            bw,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            THRESHOLD_BLOCK_SIZE,
            THRESHOLD_OFFSET,
        )

        val out = Mat()
        Imgproc.cvtColor(bw, out, Imgproc.COLOR_GRAY2RGBA)
        val result = matToBitmap(out)

        src.release()
        gray.release()
        denoise.release()
        bw.release()
        out.release()
        return result
    }

    private fun toGray(bitmap: Bitmap): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        val out = Mat()
        Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2RGBA)
        val result = matToBitmap(out)
        src.release()
        gray.release()
        out.release()
        return result
    }

    /** Boosts contrast on the lightness channel only, so text gets sharper without changing the colours. */
    private fun enhanceColor(bitmap: Bitmap): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val rgb = Mat()
        Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)

        val channels = ArrayList<Mat>(3)
        Core.split(lab, channels)
        val lightness = channels[0]
        val enhanced = Mat()
        Imgproc.createCLAHE(CLAHE_CLIP_LIMIT, CLAHE_TILE_GRID).apply(lightness, enhanced)
        channels[0] = enhanced
        Core.merge(channels, lab)

        // OpenCV has no direct Lab -> RGBA conversion, so go through RGB.
        Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
        val out = Mat()
        Imgproc.cvtColor(rgb, out, Imgproc.COLOR_RGB2RGBA)
        val result = matToBitmap(out)

        src.release()
        rgb.release()
        lab.release()
        lightness.release()
        channels.forEach { it.release() }
        out.release()
        return result
    }

    private fun matToBitmap(mat: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        return bmp
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Double {
        val dx = (x2 - x1).toDouble()
        val dy = (y2 - y1).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Orders four corners as [topLeft, topRight, bottomRight, bottomLeft]:
     * topLeft has the smallest x+y, bottomRight the largest;
     * topRight has the smallest y-x, bottomLeft the largest.
     */
    private fun orderPoints(pts: Array<Point>): Array<Point> {
        val tl = pts.minBy { it.x + it.y }
        val br = pts.maxBy { it.x + it.y }
        val tr = pts.minBy { it.y - it.x }
        val bl = pts.maxBy { it.y - it.x }
        return arrayOf(tl, tr, br, bl)
    }
}
