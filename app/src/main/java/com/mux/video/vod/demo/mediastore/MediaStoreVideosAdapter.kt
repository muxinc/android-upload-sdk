package com.mux.video.vod.demo.mediastore

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.util.Consumer
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.mux.video.upload.api.MuxUpload
import com.mux.video.vod.demo.databinding.ListItemUploadingVideoBinding
import com.mux.video.vod.demo.mediastore.model.UploadingVideo

class MediaStoreVideosAdapter(
  private var items: List<MuxUpload>,
) : RecyclerView.Adapter<MediaStoreVideoViewHolder>() {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaStoreVideoViewHolder {
    val viewBinding =
      ListItemUploadingVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return MediaStoreVideoViewHolder(viewBinding.root, viewBinding)
  }

  override fun getItemCount(): Int = items.size

  override fun onBindViewHolder(holder: MediaStoreVideoViewHolder, position: Int) {
    val listItem = items[position]
    val fileSize = listItem.videoFile.length()
    holder.viewBinding.mediastoreVideoFilename.text = listItem.videoFile.absolutePath
    holder.viewBinding.mediastoreVideoFilesize.text = "${fileSize} bytes"
//    holder.viewBinding.mediastoreVideoTitle.text = listItem.uploadInfo.title
//    holder.viewBinding.mediastoreVideoDate.text = listItem.uploadInfo.date

    listItem.addProgressConsumer(Consumer {
      holder.viewBinding.mediastoreVideoProgress.progress = (it.bytesUploaded / 10000).toInt()
      holder.viewBinding.mediastoreVideoProgress.max = (it.totalBytes / 10000).toInt()
    })

  }
}

class MediaStoreVideoViewHolder(view: View, val viewBinding: ListItemUploadingVideoBinding) :
  ViewHolder(view) {

}
