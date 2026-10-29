package com.tamdecorators.scanner.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tamdecorators.scanner.data.ScanEntity
import com.tamdecorators.scanner.databinding.ItemScanBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScansAdapter : RecyclerView.Adapter<ScansAdapter.VH>() {
    private val items = mutableListOf<ScanEntity>()
    private val fmt = SimpleDateFormat("EEE, d MMM yyyy • HH:mm", Locale.getDefault())

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<ScanEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemScanBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], fmt)
    }

    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemScanBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ScanEntity, fmt: SimpleDateFormat) {
            binding.title.text = item.title ?: "Scan"
            binding.subtitle.text = fmt.format(Date(item.createdAtEpochMs))
        }
    }
}

