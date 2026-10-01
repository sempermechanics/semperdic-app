@file:SuppressLint("NotifyDataSetChanged")

package com.indicvision.semper.ui.analysis.frames

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.indicvision.semper.R
import com.indicvision.semper.ui.common.dp
import java.io.File
import java.util.Locale

/** Horizontal strip of deformed-frame thumbs with 1…N order badges. */
class FrameOrderAdapter(
    private val onOrderChanged: (List<String>) -> Unit,
) : RecyclerView.Adapter<FrameOrderAdapter.Holder>() {

    private val paths = mutableListOf<String>()
    var dragEnabled: Boolean = false

    fun submit(paths: List<String>) {
        this.paths.clear()
        this.paths.addAll(paths)
        notifyDataSetChanged()
    }

    fun currentPaths(): List<String> = paths.toList()

    override fun getItemCount(): Int = paths.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_frame_order, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(paths[position], position + 1)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_BADGE)) {
            holder.setBadge(position + 1)
            // The refresh follows a drop, and the change animation it triggers
            // cancels the drop's shrink mid-way; settle the tile here instead.
            holder.itemView.animate().cancel()
            applyDragging(holder.itemView, dragging = false, animate = false)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    /** Move within the list; do not notify range-changed mid-drag (breaks ItemTouchHelper). */
    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in paths.indices || to !in paths.indices) return
        val item = paths.removeAt(from)
        paths.add(to, item)
        notifyItemMoved(from, to)
    }

    fun refreshBadges() {
        if (paths.isEmpty()) return
        notifyItemRangeChanged(0, paths.size, PAYLOAD_BADGE)
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumb: ImageView = itemView.findViewById(R.id.ivFrameThumb)
        private val badge: TextView = itemView.findViewById(R.id.tvFrameBadge)

        fun setBadge(order: Int) {
            badge.text = String.format(Locale.US, "%d", order)
        }

        fun bind(path: String, order: Int) {
            setBadge(order)
            applyDragging(itemView, false, animate = false)
            if (!File(path).exists()) {
                thumb.setImageResource(R.drawable.ic_photos_share)
                return
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                thumb.setImageResource(R.drawable.ic_photos_share)
                return
            }
            var sample = 1
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (longEdge / sample > THUMB_MAX_EDGE_PX) sample *= 2
            val bmp = BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
            if (bmp != null) {
                thumb.setImageBitmap(bmp)
            } else {
                thumb.setImageResource(R.drawable.ic_photos_share)
            }
        }
    }

    companion object {
        private const val PAYLOAD_BADGE = "badge"

        /** Thumbnails are a strip of small tiles; decode no larger than they draw. */
        private const val THUMB_MAX_EDGE_PX = 160
        private const val DRAG_SCALE = 1.06f
        private const val DRAG_ELEVATION_DP = 8f
        private const val DRAG_ANIM_MS = 120L

        fun applyDragging(view: View, dragging: Boolean, animate: Boolean = true) {
            val elevation = if (dragging) view.dp(DRAG_ELEVATION_DP) else 0f
            val scale = if (dragging) DRAG_SCALE else 1f
            ViewCompat.setElevation(view, elevation)
            if (animate) {
                view.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(DRAG_ANIM_MS)
                    .start()
            } else {
                view.scaleX = scale
                view.scaleY = scale
            }
        }

        fun attachDrag(recycler: RecyclerView, adapter: FrameOrderAdapter): ItemTouchHelper {
            val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                0,
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    if (!adapter.dragEnabled) return false
                    adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun isLongPressDragEnabled(): Boolean = adapter.dragEnabled

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                        applyDragging(viewHolder.itemView, dragging = true)
                        var p = recycler.parent
                        while (p != null) {
                            p.requestDisallowInterceptTouchEvent(true)
                            p = p.parent as? ViewGroup
                        }
                    }
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    applyDragging(viewHolder.itemView, dragging = false)
                    var p = recycler.parent
                    while (p != null) {
                        p.requestDisallowInterceptTouchEvent(false)
                        p = p.parent as? ViewGroup
                    }
                    adapter.refreshBadges()
                    adapter.onOrderChanged(adapter.currentPaths())
                }
            })
            helper.attachToRecyclerView(recycler)
            return helper
        }
    }
}
