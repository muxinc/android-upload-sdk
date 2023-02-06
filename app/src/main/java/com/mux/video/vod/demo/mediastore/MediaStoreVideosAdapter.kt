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

  private var progressConsumer: Consumer<MuxUpload.State>? = null

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaStoreVideoViewHolder {
    val viewBinding =
      ListItemUploadingVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return MediaStoreVideoViewHolder(viewBinding.root, viewBinding)
  }

  override fun getItemCount(): Int = items.size

  override fun onBindViewHolder(holder: MediaStoreVideoViewHolder, position: Int) {
    val listItem = items[position]
    val fileSize = listItem.videoFile.length()

//    progressConsumer?.let { listItem.removeProgressConsumer(it) }
//    val newConsumer = Consumer<MuxUpload.State> {
//       TODO: This sucks, should be listening from viewmodel
//      holder.viewBinding.mediastoreVideoProgress.progress = (it.bytesUploaded / 10000).toInt()
//      holder.viewBinding.mediastoreVideoProgress.max = (it.totalBytes / 10000).toInt()
//    }
//    progressConsumer = newConsumer
//    listItem.addProgressConsumer(newConsumer)

    val elapsedTime = listItem.currentState.updatedTime - listItem.currentState.startTime;
    val bytesPerSec = (listItem.currentState.bytesUploaded / elapsedTime.toDouble()) //* 1000.0
    val stateMsg = if (listItem.currentState.bytesUploaded >= listItem.currentState.totalBytes) {
      "done"
    } else {
      "not done"
    }

    holder.viewBinding.mediastoreVideoTitle.text = stateMsg
    holder.viewBinding.mediastoreVideoProgress.progress =
      (listItem.currentState.bytesUploaded / 10000).toInt()
    holder.viewBinding.mediastoreVideoProgress.max =
      (listItem.currentState.totalBytes / 10000).toInt()
    holder.viewBinding.mediastoreVideoFilename.text = listItem.videoFile.absolutePath
    holder.viewBinding.mediastoreVideoDate.text =
      "${listItem.currentState.bytesUploaded} bytes in ${elapsedTime / 1000F} ms elapsed "
    holder.viewBinding.mediastoreVideoFilesize.text = "$bytesPerSec Bytes/s"
  }
}

class MediaStoreVideoViewHolder(view: View, val viewBinding: ListItemUploadingVideoBinding) :
  ViewHolder(view) {

}
