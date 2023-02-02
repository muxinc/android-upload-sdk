package com.mux.video.vod.demo.mediastore

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.mux.video.vod.demo.databinding.ListItemMediastoreVideoBinding
import com.mux.video.vod.demo.mediastore.model.MediaStoreVideo

class MediaStoreVideosAdapter(
  private var items: List<MediaStoreVideo>,
  private var onItemClicked: (MediaStoreVideo) -> Unit,
) : RecyclerView.Adapter<MediaStoreVideoViewHolder>() {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaStoreVideoViewHolder {
    val viewBinding =
      ListItemMediastoreVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return MediaStoreVideoViewHolder(viewBinding.root, viewBinding)
  }

  override fun getItemCount(): Int = items.size

  override fun onBindViewHolder(holder: MediaStoreVideoViewHolder, position: Int) {
    val listItem = items[position]
    val fileSize = listItem.file.length()
    holder.viewBinding.mediastoreVideoFilename.text = listItem.file.absolutePath
    holder.viewBinding.mediastoreVideoFilesize.text = "${fileSize} bytes"
    holder.viewBinding.mediastoreVideoTitle.text = listItem.title
    holder.viewBinding.mediastoreVideoDate.text = listItem.date

    holder.itemView.setOnClickListener { onItemClicked(listItem) }
  }
}

class MediaStoreVideoViewHolder(view: View, val viewBinding: ListItemMediastoreVideoBinding) :
  ViewHolder(view) {

}
