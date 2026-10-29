package com.tamdecorators.scanner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtEpochMs: Long,
    val title: String?,
    val imagePath: String,
    val pdfPath: String?,
    val ocrText: String,
    val customer: String?,
    val date: String?,
    val invoiceNumber: String?,
    val total: String?,
    val vat: String?,
)

