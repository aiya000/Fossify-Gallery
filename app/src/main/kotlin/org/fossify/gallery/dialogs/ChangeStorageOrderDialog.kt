package org.fossify.gallery.dialogs

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.gallery.R
import org.fossify.gallery.databinding.DialogChangeStorageOrderBinding
import org.fossify.gallery.databinding.ItemStorageOrderBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.storageLabel
import java.util.Collections

// The order the storages stand in, in the storage menu, under a sideways swipe and along the
// pickers' chips (#128). All four are listed, a storage not set up yet included, so that it
// already has its place when it is set up; "All storages" moves like the rest.
// A row is dragged by its handle, or by a long press anywhere on it
class ChangeStorageOrderDialog(val activity: BaseSimpleActivity, val callback: () -> Unit) {
    private val order = activity.config.storageOrder.toMutableList()
    private val binding = DialogChangeStorageOrderBinding.inflate(activity.layoutInflater)

    init {
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val fromPosition = from.bindingAdapterPosition
                val toPosition = to.bindingAdapterPosition
                Collections.swap(order, fromPosition, toPosition)
                recyclerView.adapter?.notifyItemMoved(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })

        binding.changeStorageOrderList.adapter = StorageOrderAdapter(touchHelper)
        touchHelper.attachToRecyclerView(binding.changeStorageOrderList)

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                activity.config.storageOrder = order
                callback()
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.storage_order)
            }
    }

    private inner class StorageOrderAdapter(val touchHelper: ItemTouchHelper) : RecyclerView.Adapter<StorageOrderAdapter.ViewHolder>() {
        private val textColor = activity.getProperTextColor()

        inner class ViewHolder(val row: ItemStorageOrderBinding) : RecyclerView.ViewHolder(row.root)

        override fun getItemCount() = order.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(ItemStorageOrderBinding.inflate(activity.layoutInflater, parent, false))

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.row.apply {
                storageOrderLabel.text = activity.storageLabel(order[position])
                storageOrderLabel.setTextColor(textColor)
                storageOrderDragHandle.applyColorFilter(textColor)
                storageOrderDragHandle.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        touchHelper.startDrag(holder)
                    }
                    false
                }
            }
        }
    }
}
