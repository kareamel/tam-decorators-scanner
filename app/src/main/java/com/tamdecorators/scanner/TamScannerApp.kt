package com.tamdecorators.scanner

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class TamScannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val ok = OpenCVLoader.initDebug()
        Log.d("TamScanner", "OpenCV init: $ok")
    }
}

