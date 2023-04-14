package com.mux.video.vod.demo.upload.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import com.mux.video.upload.api.UploadEventListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Queries the device's content provider for saved videos to upload
 */
class UploadListViewModel(app: Application) : AndroidViewModel(app) {

  val uploads: LiveData<List<MuxUpload>> by this::_uploads
  private val _uploads = MutableLiveData<List<MuxUpload>>(listOf())

  val uploadsFlow: Flow<List<MuxUpload>> get() = _uploadsFlow
  private val _uploadsFlow: MutableStateFlow<List<MuxUpload>> = MutableStateFlow(listOf())

  private val uploadMap = mutableMapOf<File, MuxUpload>()

  private val listUpdateListener: UploadEventListener<List<MuxUpload>> by lazy {
    UploadEventListener {  newUploads ->
      newUploads.forEach { uploadMap[it.videoFile] = it }
      updateUiData(uploadMap.values.toList())
      refreshList()
    }
  }

  private var observeListJob: Job? = null

  fun refreshList() {
    MuxUploadManager.addUploadsUpdatedListener(listUpdateListener)

    // The SDK ensures that there's only 1 upload job running for a file, so get/make as many
    // MuxUploads as you like. You don't need to hold onto MuxUploads or clean them up.
    val recentUploads = MuxUploadManager.allUploadJobs()

    uploadMap.apply {
      recentUploads.forEach { put(it.videoFile, it) }
    }
    val uploadList = uploadMap.values.toList()
    updateUiData(uploadList)

    observeListJob?.cancel()
    observeListJob = viewModelScope.launch {
      recentUploads.forEach { upload ->
        upload.addProgressListener {
          uploadMap[upload.videoFile] = upload
          updateUiData(uploadList)
        }
      } // recentUploads.forEach
    } // observeListJob = ...
  }

  override fun onCleared() {
    super.onCleared()
    observeListJob?.cancel()
    MuxUploadManager.removeUploadsUpdatedListener(listUpdateListener)
  }

  private fun updateUiData(list: List<MuxUpload>) {
    _uploads.value = list
    _uploadsFlow.value = list
  }

  init {
    refreshList()
  }
}
