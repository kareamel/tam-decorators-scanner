package com.tamdecorators.scanner.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.tamdecorators.scanner.R
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

object PdfExporter {
    fun exportSinglePage(
        context: Context,
        bitmap: Bitmap,
        outFile: File,
        title: String = context.getString(R.string.company_name),
    ) {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(2480, 3508, 1).create() // ~A4 @300dpi
        val page = doc.startPage(pageInfo)

        val canvas = page.canvas

        val topPad = 56f
        val sidePad = 120f

        val headerBottomY =
            BitmapFactory.decodeResource(context.resources, R.drawable.tam_decorators_logo)?.let { logoRaw ->
                val maxLogoHeight = pageInfo.pageHeight * 0.065f
                val maxLogoWidth = pageInfo.pageWidth - sidePad * 2f
                val scaleFactor = min(
                    maxLogoHeight / logoRaw.height.toFloat(),
                    maxLogoWidth / logoRaw.width.toFloat(),
                )
                val lw = (logoRaw.width * scaleFactor).toInt().coerceAtLeast(1)
                val lh = (logoRaw.height * scaleFactor).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(logoRaw, lw, lh, true)
                canvas.drawBitmap(scaled, sidePad, topPad, null)
                if (scaled != logoRaw) {
                    scaled.recycle()
                }
                logoRaw.recycle()
                topPad + lh + 56f
            } ?: run {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF092ABE.toInt()
                    textSize = 56f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                canvas.drawText(title, sidePad, 110f, paint)
                200f
            }

        val headerH = headerBottomY.toInt().coerceAtLeast(200)

        val availableW = pageInfo.pageWidth - 240
        val availableH = pageInfo.pageHeight - headerH - 240
        val scaleImg = min(
            availableW.toFloat() / bitmap.width.toFloat(),
            availableH.toFloat() / bitmap.height.toFloat(),
        )
        val drawW = bitmap.width * scaleImg
        val drawH = bitmap.height * scaleImg
        val left = (pageInfo.pageWidth - drawW) / 2f
        val top = headerH + (availableH - drawH) / 2f

        val pageBitmap =
            Bitmap.createScaledBitmap(bitmap, drawW.toInt(), drawH.toInt(), true)
        canvas.drawBitmap(pageBitmap, left, top, null)
        if (pageBitmap != bitmap) {
            pageBitmap.recycle()
        }

        doc.finishPage(page)

        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
    }
}
