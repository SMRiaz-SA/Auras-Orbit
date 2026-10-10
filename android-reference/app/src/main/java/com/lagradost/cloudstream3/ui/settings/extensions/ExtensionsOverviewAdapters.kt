package com.lagradost.cloudstream3.ui.settings.extensions

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.databinding.ExtensionEmptyStateBinding
import com.lagradost.cloudstream3.databinding.ExtensionSectionHeaderBinding
import java.text.NumberFormat

class ExtensionsSectionHeaderAdapter(
    private val title: String,
    private val actionText: String? = null,
    private val onAction: (() -> Unit)? = null,
) : RecyclerView.Adapter<ExtensionsSectionHeaderAdapter.ViewHolder>() {
    private var count: Int? = null

    class ViewHolder(val binding: ExtensionSectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ExtensionSectionHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.apply {
            sectionTitle.text = title
            sectionCount.isVisible = count != null
            sectionCount.text = count?.let { NumberFormat.getIntegerInstance().format(it) }.orEmpty()
            sectionAction.isVisible = actionText != null && onAction != null
            sectionAction.text = actionText.orEmpty()
            sectionAction.setOnClickListener { onAction?.invoke() }
        }
    }

    override fun getItemCount() = 1

    fun updateCount(count: Int) {
        this.count = count
        notifyItemChanged(0)
    }
}

class ExtensionsEmptyStateAdapter(
    private val title: String,
    private val message: String,
) : RecyclerView.Adapter<ExtensionsEmptyStateAdapter.ViewHolder>() {
    private var visible = false

    class ViewHolder(val binding: ExtensionEmptyStateBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ExtensionEmptyStateBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.emptyTitle.text = title
        holder.binding.emptyMessage.text = message
    }

    override fun getItemCount() = if (visible) 1 else 0

    fun setVisible(visible: Boolean) {
        if (this.visible == visible) return
        this.visible = visible
        if (visible) notifyItemInserted(0) else notifyItemRemoved(0)
    }
}
