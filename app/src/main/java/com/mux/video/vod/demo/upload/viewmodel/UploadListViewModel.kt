package com.mux.video.vod.demo.upload.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import com.mux.video.upload.api.UploadEventListener
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.internal.toImmutableList
import java.io.File

/**
 * Queries the device's content provider for saved videos to upload
 */
class UploadListViewModel(app: Application) : AndroidViewModel(app) {

  val uploads: LiveData<List<MuxUpload>> by this::_uploads
  private val _uploads = MutableLiveData<List<MuxUpload>>(listOf())

  private val uploadMap = mutableMapOf<File, MuxUpload>()

  private val listUpdateListener: UploadEventListener<List<MuxUpload>> by lazy {
    UploadEventListener { newUploads ->
      newUploads.forEach { uploadMap[it.videoFile] = it }
      updateUiData(uploadMap.values.toList())
    }
  }

  fun refreshList() {
    MuxUploadManager.addUploadsUpdatedListener(listUpdateListener)

    // The SDK ensures that there's only 1 upload job running for a file, so get/make as many
    // MuxUploads as you like. You don't need to hold onto MuxUploads or clean them up.
    val recentUploads = MuxUploadManager.allUploadJobs()

    uploadMap.apply {
      recentUploads.forEach { put(it.videoFile, it) }
    }
    val uploadList = uploadMap.values.toList()
    observeUploads(uploadList)
    updateUiData(uploadList)
  }

  override fun onCleared() {
    super.onCleared()
    MuxUploadManager.removeUploadsUpdatedListener(listUpdateListener)
  }

  private fun observeUploads(recentUploads: List<MuxUpload>) {
    recentUploads.forEach { upload ->
      upload.setProgressListener {
        Log.d("UploadListViewModel", "upload progress: $it")
        uploadMap[upload.videoFile] = upload
        updateUiData(uploadMap.values.toList())
      }
    } // recentUploads.forEach
  }

  private fun updateUiData(list: List<MuxUpload>) {
    Log.d("UploadListViewModel", "updated with list items $list")
    _uploads.value = list.toImmutableList()
  }

  init {
    refreshList()
  }
}
