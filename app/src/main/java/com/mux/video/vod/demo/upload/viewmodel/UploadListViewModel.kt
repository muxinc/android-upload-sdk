package com.mux.video.vod.demo.upload.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import com.mux.video.vod.demo.upload.UploadListActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Queries the device's content provider for saved videos to upload
 */
class UploadListViewModel(app: Application) : AndroidViewModel(app) {

  val uploads: LiveData<List<MuxUpload>> by this::innerUploads
  private val innerUploads = MutableLiveData<List<MuxUpload>>(listOf())
  private val uploadMap = mutableMapOf<File, MuxUpload>()

  private var observeListJob: Job? = null

  fun refreshList() {
    // The SDK ensures that there's only 1 upload job running for a file, so get/make as many
    // MuxUploads as you like. You don't need to hold onto MuxUploads or clean them up.
    val recentUploads = MuxUploadManager.allUploadJobs()

    uploadMap.apply {
      recentUploads.forEach { put(it.videoFile, it) }
    }
    innerUploads.value = uploadMap.values.toList()

    observeListJob?.cancel()
    observeListJob = viewModelScope.launch {
      recentUploads.forEach { upload ->
        upload.addProgressListener {
          uploadMap[upload.videoFile] = upload
          innerUploads.value = uploadMap.values.toList()
        }
      } // recentUploads.forEach
    } // observeListJob = ...
  }

  init {
    refreshList()
  }
}
