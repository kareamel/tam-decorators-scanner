package com.tamdecorators.scanner.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tamdecorators.scanner.data.AppDb
import com.tamdecorators.scanner.data.ScanEntity
import com.tamdecorators.scanner.databinding.ActivityEditScanBinding
import com.tamdecorators.scanner.util.InvoiceFieldExtractor
import com.tamdecorators.scanner.util.PdfExporter
import com.tamdecorators.scanner.util.ScanFilter
import com.tamdecorators.scanner.util.ScanProcessor
import com.tamdecorators.scanner.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditScanActivity : ComponentActivity() {
    private lateinit var binding: ActivityEditScanBinding

    private lateinit var sourceFile: File
    private var baseBitmap: Bitmap? = null // cropped + upright
    private var currentBitmap: Bitmap? = null
    private var currentFilter: ScanFilter = ScanFilter.BW

    // One recognizer for the whole screen, reused every time the filter changes.
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: run { finish(); return }
        sourceFile = File(path)

        // View binding turns layout ids like "filter_bw" into camelCase fields ("filterBw").
        binding.filterGroup.check(binding.filterBw.id)
        binding.filterGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentFilter = when (checkedId) {
                binding.filterBw.id -> ScanFilter.BW
                binding.filterColor.id -> ScanFilter.COLOR
                binding.filterGray.id -> ScanFilter.GRAY
                else -> ScanFilter.PHOTO
            }
            renderAndOcr()
        }

        binding.save.setOnClickListener { saveToHistory() }
        binding.shareWhatsapp.setOnClickListener { shareToWhatsApp() }

        lifecycleScope.launch {
            val upright = withContext(Dispatchers.Default) { ImageUtils.decodeUprightBitmap(sourceFile) }
            val quad = scaleQuadToBitmap(
                quad = intent.getFloatArrayExtra(EXTRA_UPRIGHT_QUAD),
                fromW = intent.getIntExtra(EXTRA_UPRIGHT_W, 0),
                fromH = intent.getIntExtra(EXTRA_UPRIGHT_H, 0),
                bitmap = upright,
            )
            val cropped = withContext(Dispatchers.Default) { ScanProcessor.perspectiveCrop(upright, quad) }
            baseBitmap = cropped
            renderAndOcr()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        textRecognizer.close()
    }

    /**
     * The corners were found on the small analysis frame (e.g. 720x1280), but the
     * photo is much larger, so scale them up to the photo's size before cropping.
     */
    private fun scaleQuadToBitmap(quad: FloatArray?, fromW: Int, fromH: Int, bitmap: Bitmap): FloatArray? {
        if (quad == null || fromW <= 0 || fromH <= 0) return null
        val sx = bitmap.width.toFloat() / fromW
        val sy = bitmap.height.toFloat() / fromH
        return FloatArray(quad.size) { i -> if (i % 2 == 0) quad[i] * sx else quad[i] * sy }
    }

    private fun renderAndOcr() {
        val base = baseBitmap ?: return
        lifecycleScope.launch {
            val filtered = withContext(Dispatchers.Default) { ScanProcessor.applyFilter(base, currentFilter) }
            currentBitmap = filtered
            binding.preview.setImageBitmap(filtered)
            runOcr(filtered)
        }
    }

    /** Reads the text on-device with ML Kit, then pulls the invoice fields out of it. */
    private fun runOcr(bitmap: Bitmap) {
        val img = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(img)
            .addOnFailureListener { e -> Log.e(TAG, "Text recognition failed", e) }
            .addOnSuccessListener { result ->
                val text = result.text.orEmpty()
                binding.extractedText.setText(text)
                val fields = InvoiceFieldExtractor.extract(text)
                binding.fieldCustomer.setText(fields.customer.orEmpty())
                binding.fieldDate.setText(fields.date.orEmpty())
                binding.fieldInvoiceNumber.setText(fields.invoiceNumber.orEmpty())
                binding.fieldTotal.setText(fields.total.orEmpty())
                binding.fieldVat.setText(fields.vat.orEmpty())
            }
    }

    private fun saveToHistory() {
        val bmp = currentBitmap ?: return
        lifecycleScope.launch {
            val createdAt = System.currentTimeMillis()
            val scansDir = File(filesDir, "scans").apply { mkdirs() }
            val imgFile = File(scansDir, "scan_$createdAt.jpg")
            val pdfFile = File(scansDir, "scan_$createdAt.pdf")

            withContext(Dispatchers.IO) {
                imgFile.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                PdfExporter.exportSinglePage(this@EditScanActivity, bmp, pdfFile)
            }

            val ocrText = binding.extractedText.text?.toString().orEmpty()
            val scan = ScanEntity(
                createdAtEpochMs = createdAt,
                title = binding.fieldInvoiceNumber.text?.toString()?.takeIf { it.isNotBlank() },
                imagePath = imgFile.absolutePath,
                pdfPath = pdfFile.absolutePath,
                ocrText = ocrText,
                customer = binding.fieldCustomer.text?.toString()?.takeIf { it.isNotBlank() },
                date = binding.fieldDate.text?.toString()?.takeIf { it.isNotBlank() },
                invoiceNumber = binding.fieldInvoiceNumber.text?.toString()?.takeIf { it.isNotBlank() },
                total = binding.fieldTotal.text?.toString()?.takeIf { it.isNotBlank() },
                vat = binding.fieldVat.text?.toString()?.takeIf { it.isNotBlank() },
            )
            withContext(Dispatchers.IO) {
                AppDb.get(this@EditScanActivity).scanDao().insert(scan)
            }
            finish()
        }
    }

    private fun shareToWhatsApp() {
        val bmp = currentBitmap ?: return
        lifecycleScope.launch {
            val tmpDir = File(cacheDir, "share").apply { mkdirs() }
            val pdfFile = File(tmpDir, "tam_scan_share.pdf")
            withContext(Dispatchers.IO) {
                PdfExporter.exportSinglePage(this@EditScanActivity, bmp, pdfFile)
            }
            val uri = FileProvider.getUriForFile(
                this@EditScanActivity,
                "${packageName}.fileprovider",
                pdfFile,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage("com.whatsapp")
            }
            try {
                startActivity(send)
            } catch (_: Throwable) {
                // Fallback to generic share
                val generic = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(generic, "Share"))
            }
        }
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_UPRIGHT_QUAD = "upright_quad"
        const val EXTRA_UPRIGHT_W = "upright_w"
        const val EXTRA_UPRIGHT_H = "upright_h"
        private const val TAG = "EditScan"
    }
}

