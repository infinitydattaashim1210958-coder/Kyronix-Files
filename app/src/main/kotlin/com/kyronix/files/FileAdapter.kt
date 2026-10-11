package com.kyronix.files

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for file/directory listings.
 *
 * Features
 * ─────────
 * - List view (default) and 3-column grid view — toggle via [setViewMode].
 * - Multi-selection state tracked by file path (stable across list updates).
 * - DiffUtil-based updates so only changed rows are rebound (avoids full
 *   notifyDataSetChanged flicker on MIUI's compositor).
 * - Stable IDs keyed to path hash — prevents RecyclerView from crashing when
 *   the list is updated while the Activity is partially paused on HyperOS.
 */
class FileAdapter(
    private val onClick      : (FileItem) -> Unit,
    private val onLongClick  : (FileItem) -> Boolean,
    private val onOverflow   : (FileItem, View) -> Unit,
) : RecyclerView.Adapter<FileAdapter.FileVH>() {

    enum class ViewMode { LIST, GRID }

    // ── State ─────────────────────────────────────────────────────────────────

    private val items          = mutableListOf<FileItem>()
    private val selectedPaths  = mutableSetOf<String>()
    var viewMode: ViewMode     = ViewMode.LIST
        private set
    var selectionMode: Boolean = false
        private set

    val selectedCount: Int get() = selectedPaths.size
    val selectedItems: List<FileItem> get() =
        items.filter { it.file.absolutePath in selectedPaths }

    init { setHasStableIds(true) }

    // ── Data ──────────────────────────────────────────────────────────────────

    fun submitList(newItems: List<FileItem>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) =
                items[o].file.absolutePath == newItems[n].file.absolutePath
            override fun areContentsTheSame(o: Int, n: Int) =
                items[o] == newItems[n]
        })
        items.clear()
        items.addAll(newItems)
        // Drop stale selections no longer in the new list
        val newPaths = newItems.map { it.file.absolutePath }.toHashSet()
        selectedPaths.retainAll(newPaths)
        diff.dispatchUpdatesTo(this)
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    fun enterSelectionMode() {
        if (selectionMode) return
        selectionMode = true
        @SuppressLint("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        if (!selectionMode) return
        selectionMode = false
        selectedPaths.clear()
        @SuppressLint("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun toggleSelection(item: FileItem) {
        val path = item.file.absolutePath
        if (!selectedPaths.add(path)) selectedPaths.remove(path)
        val pos = items.indexOfFirst { it.file.absolutePath == path }
        if (pos >= 0) notifyItemChanged(pos)
    }

    fun selectAll() {
        items.forEach { selectedPaths.add(it.file.absolutePath) }
        @SuppressLint("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedPaths.clear()
        @SuppressLint("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    // ── View mode ─────────────────────────────────────────────────────────────

    fun setViewMode(mode: ViewMode) {
        if (viewMode == mode) return
        viewMode = mode
        @SuppressLint("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    // ── RecyclerView.Adapter ─────────────────────────────────────────────────

    override fun getItemViewType(position: Int) =
        if (viewMode == ViewMode.GRID) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileVH {
        val layout = if (viewType == 1) R.layout.item_file_grid else R.layout.item_file_list
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return FileVH(view)
    }

    override fun onBindViewHolder(holder: FileVH, position: Int) {
        val item     = items[position]
        val selected = item.file.absolutePath in selectedPaths
        holder.bind(item, selected, selectionMode, viewMode, onClick, onLongClick, onOverflow)
    }

    override fun getItemCount() = items.size

    // Stable ID = hash of absolute path — survives list reorder without crash
    override fun getItemId(position: Int): Long =
        items[position].file.absolutePath.hashCode().toLong()

    // ── ViewHolder ────────────────────────────────────────────────────────────

    class FileVH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val ivIcon    : ImageView   = itemView.findViewById(R.id.iv_file_icon)
        private val tvName    : TextView    = itemView.findViewById(R.id.tv_file_name)
        private val tvMeta    : TextView?   = itemView.findViewById(R.id.tv_file_meta)
        private val ivCheck   : ImageView?  = itemView.findViewById(R.id.iv_checkbox)
        private val btnMore   : ImageButton? = itemView.findViewById(R.id.btn_overflow)

        fun bind(
            item         : FileItem,
            selected     : Boolean,
            selectionMode: Boolean,
            viewMode     : ViewMode,
            onClick      : (FileItem) -> Unit,
            onLongClick  : (FileItem) -> Boolean,
            onOverflow   : (FileItem, View) -> Unit,
        ) {
            // Icon
            ivIcon.setImageResource(item.iconResId())

            // Name
            tvName.text = item.name

            // Meta line (list only; grid hides it via 0dp height)
            tvMeta?.text = when {
                item.isDirectory            -> item.displayDate
                item.displayDate.isEmpty()  -> item.displaySize
                else -> "${item.displaySize}  ·  ${item.displayDate}"
            }

            // Selection checkbox
            ivCheck?.let {
                it.visibility = if (selectionMode) View.VISIBLE else View.GONE
                it.setImageResource(
                    if (selected) R.drawable.ic_checkbox_checked
                    else          R.drawable.ic_checkbox_unchecked
                )
            }

            // Activated state drives the selected-background tint via state list drawable
            itemView.isActivated = selected

            // Overflow button (only in list mode layout)
            btnMore?.setOnClickListener { v -> onOverflow(item, v) }

            // Click / long-click
            itemView.setOnClickListener     { onClick(item) }
            itemView.setOnLongClickListener { onLongClick(item) }
        }
    }
}
