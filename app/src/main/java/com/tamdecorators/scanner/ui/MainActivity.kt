package com.tamdecorators.scanner.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tamdecorators.scanner.data.AppDb
import com.tamdecorators.scanner.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private val adapter = ScansAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scansRecycler.layoutManager = LinearLayoutManager(this)
        binding.scansRecycler.adapter = adapter

        binding.cameraFab.setOnClickListener {
            startActivity(Intent(this, ScanCameraActivity::class.java))
        }

        lifecycleScope.launch {
            AppDb.get(this@MainActivity).scanDao().observeAll()
                .collect { adapter.submit(it) }
        }
    }
}

