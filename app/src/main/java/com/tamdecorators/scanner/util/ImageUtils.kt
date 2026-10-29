package com.tamdecorators.scanner.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

object ImageUtils {
    fun decodeUprightBitmap(file: File, maxDim: Int = 2400): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val scale = max(1, max(opts.outWidth, opts.outHeight) / maxDim)
        val opts2 = BitmapFactory.Options().apply { inSampleSize = scale }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts2)
            ?: error("Failed to decode bitmap")
        return rotateIfNeeded(bmp, file)
    }

    private fun rotateIfNeeded(bitmap: Bitmap, file: File): Bitmap {
        val exif = ExifInterface(file)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (rotation == 0) return bitmap
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }
}

