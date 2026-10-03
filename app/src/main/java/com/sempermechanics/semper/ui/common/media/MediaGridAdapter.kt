package com.sempermechanics.semper.ui.common.media

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.sempermechanics.semper.databinding.ItemMediaTileBinding
import java.util.concurrent.Executors

/**
 * Thumbnail grid for [MediaPickerSheet]. Selection lives on the sheet.
 * Thumbnails come from MediaStore through [resolver].
 */
class MediaGridAdapter(
    private val resolver: ContentResolver,
    private val isSelected: (Uri) -> Boolean,
    private val onClick: (MediaStoreBrowser.Item) -> Unit,
) : RecyclerView.Adapter<MediaGridAdapter.Holder>() {

    private var items: List<MediaStoreBrowser.Item> = emptyList()
    private val executor = Executors.newFixedThreadPool(DECODE_THREADS)
    private val thumbs = ThumbnailLoader(THUMBS_CACHED, executor, ::loadThumb)

    fun indexOf(uri: Uri): Int = items.indexOfFirst { it.uri == uri }

    @SuppressLint("NotifyDataSetChanged") // a new gallery query replaces the whole grid
    fun submit(newItems: List<MediaStoreBrowser.Item>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun shutdown() {
        executor.shutdownNow()
        thumbs.clear()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemMediaTileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.video.isVisible = item.isVideo
        holder.check.isVisible = isSelected(item.uri)
        holder.itemView.setOnClickListener { onClick(item) }
        thumbs.bind(holder.thumb, item)
    }

    /**
     * MediaStore's own thumbnail, on a decode thread. A failure is not
     * remembered: the next bind asks again, as the grid always has.
     */
    private fun loadThumb(item: MediaStoreBrowser.Item): ThumbnailLoader.Decoded {
        val bitmap = runCatching { platformThumbnail(item) }.getOrNull()
        return if (bitmap != null) ThumbnailLoader.Decoded.Loaded(bitmap) else ThumbnailLoader.Decoded.Missing
    }

    private fun platformThumbnail(item: MediaStoreBrowser.Item): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(item.uri, Size(THUMB_EDGE, THUMB_EDGE), null)
        } else {
            @Suppress("DEPRECATION")
            if (item.isVideo) {
                MediaStore.Video.Thumbnails.getThumbnail(
                    resolver,
                    item.id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null,
                )
            } else {
                MediaStore.Images.Thumbnails.getThumbnail(
                    resolver,
                    item.id,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null,
                )
            }
        }

    class Holder(row: ItemMediaTileBinding) : RecyclerView.ViewHolder(row.root) {
        val thumb = row.imgMediaThumb
        val video = row.imgMediaVideo
        val check = row.imgMediaCheck
    }

    private companion object {
        const val THUMB_EDGE = 256
        const val DECODE_THREADS = 2

        /** Enough for a few screens of the three-column grid; it used to keep every thumbnail ever shown. */
        const val THUMBS_CACHED = 60
    }
}
