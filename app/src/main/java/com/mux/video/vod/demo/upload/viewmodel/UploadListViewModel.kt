package com.mux.video.vod.demo.upload.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mux.video.upload.api.MuxUpload
import com.mux.video.vod.demo.upload.UploadListActivity
import kotlinx.coroutines.launch
import java.io.File

/**
 * Queries the device's content provider for saved videos to upload
 */
class UploadListViewModel(private val app: Application) : AndroidViewModel(app) {

  val uploads: LiveData<List<MuxUpload>> by this::innerUploads
  private val innerUploads = MutableLiveData<List<MuxUpload>>(listOf())
  private val uploadList: MutableList<MuxUpload> = mutableListOf()

  fun beginUpload(contentUri: Uri) {
    viewModelScope.launch {
      Log.d(javaClass.simpleName, "Beginning upload of uri $contentUri")
      val copiedFile = File(contentUri.path!!)//copyIntoTempFile(contentUri)
      Log.d(javaClass.simpleName, "Copied file to $copiedFile")

      val upl = MuxUpload.Builder(UploadListActivity.PUT_URL, copiedFile).build()
      upl.addProgressListener {
        //Log.v(javaClass.simpleName, "Upload progress: ${it.bytesUploaded} / ${it.totalBytes}")
        innerUploads.postValue(uploadList)
      }
      upl.addResultListener {
        if (it.isSuccess) {
          Log.w(javaClass.simpleName, "YAY! Uploaded the file: $contentUri")
          Log.i(javaClass.simpleName, "final state is $it")
          innerUploads.postValue(uploadList)
        } else {
          innerUploads.postValue(uploadList)
        }
      }
      uploadList += upl
      innerUploads.postValue(uploadList)

      upl.start()
    }
  }
}
