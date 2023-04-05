package com.mux.video.vod.demo.mediastore

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.util.Consumer
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.mux.video.upload.api.MuxUpload
import com.mux.video.vod.demo.databinding.ListItemUploadingVideoBinding
import java.text.DecimalFormat

class MediaStoreVideosAdapter(
  private var items: List<MuxUpload>,
  private var viewModel: MediaStoreVideosViewModel,
) : RecyclerView.Adapter<MediaStoreVideoViewHolder>() {

  private var progressConsumer: Consumer<MuxUpload.Progress>? = null

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaStoreVideoViewHolder {
    val viewBinding =
      ListItemUploadingVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return MediaStoreVideoViewHolder(viewBinding.root, viewBinding)
  }

  override fun getItemCount(): Int = items.size

  override fun onBindViewHolder(holder: MediaStoreVideoViewHolder, position: Int) {
    val listItem = items[position]
    val elapsedTime = listItem.currentState.updatedTime - listItem.currentState.startTime;
    val bytesPerMs = (listItem.currentState.bytesUploaded / elapsedTime.toDouble()) //* 1000.0
    val stateMsg = if (listItem.currentState.bytesUploaded >= listItem.currentState.totalBytes) {
      "done"
    } else {
      "not done"
    }
    val progressPercent =
      (listItem.currentState.bytesUploaded / listItem.currentState.totalBytes.toDouble()) * 100.0
    val df = DecimalFormat("#.00")
    val formattedRate = df.format(bytesPerMs)

    holder.viewBinding.mediastoreVideoTitle.text = stateMsg
    holder.viewBinding.mediastoreVideoProgress.progress = progressPercent.toInt()
    holder.viewBinding.mediastoreVideoProgress.max = 100
    holder.viewBinding.mediastoreVideoFilename.text = listItem.videoFile.absolutePath
    holder.viewBinding.mediastoreVideoDate.text =
      "${listItem.currentState.bytesUploaded} bytes in ${elapsedTime / 1000F} s elapsed "
    holder.viewBinding.mediastoreVideoFilesize.text = "${formattedRate} KBytes/s"

    if (listItem.currentState.isRunning) {
      holder.viewBinding.mediastoreVideoPause.setImageResource(android.R.drawable.ic_media_pause)
    } else {
      holder.viewBinding.mediastoreVideoPause.setImageResource(android.R.drawable.ic_media_play)
    }

    holder.viewBinding.mediastoreVideoPause.setOnClickListener {
      if (listItem.currentState.isRunning) {
        listItem.pause()
      } else {
        listItem.start()
      }
    }
  }
}

class MediaStoreVideoViewHolder(view: View, val viewBinding: ListItemUploadingVideoBinding) :
  ViewHolder(view) {

}
