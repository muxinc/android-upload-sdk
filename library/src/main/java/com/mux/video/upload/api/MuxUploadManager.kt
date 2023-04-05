package com.mux.video.upload.api

import android.util.Log
import androidx.annotation.MainThread
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.internal.*
import com.mux.video.upload.internal.UploadInfo
import com.mux.video.upload.internal.assertMainThread
import com.mux.video.upload.internal.startUploadJob
import com.mux.video.upload.internal.forgetUploadState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.FlowCollector
import java.io.File

object MuxUploadManager {

  private val mainScope = MainScope()

  // TODO: The production version will keep a persistent cache of
  private val uploadsByFilename: MutableMap<String, UploadInfo> = mutableMapOf()
  private val observerJobsByFilename: MutableMap<String, Job> = mutableMapOf()
  private val logger get() = MuxUploadSdk.logger

  /**
   * Finds an in-progress (or recently-failed) upload and returns an object to track it, if it was
   * in progress
   */
  fun findUploadByFile(videoFile: File): MuxUpload? =
    uploadsByFilename[videoFile.absolutePath]?.let { MuxUpload.create(it) }

  /**
   * Adds a new job to this manager.
   * If it's not started, it will be started
   * If it is started, it will be restarted with new parameters
   */
  @JvmSynthetic
  @MainThread
  internal fun startJob(upload: UploadInfo, restart: Boolean = false): UploadInfo {
    assertMainThread()
    return insertOrUpdateUpload(upload, restart)
  }

  @JvmSynthetic
  @MainThread
  internal fun pauseJob(upload: UploadInfo): UploadInfo {
    assertMainThread()
    // Paused jobs stay in the manager and remain persisted
    uploadsByFilename[upload.file.absolutePath]?.let {
      cancelJobInner(it)
      val pausedUpload = upload.update(
        uploadJob = null,
        progressChannel = null,
        errorChannel = null,
        successChannel = null,
      )
      uploadsByFilename[pausedUpload.file.absolutePath] = pausedUpload
      return pausedUpload
    }
    return upload
  }

  @JvmSynthetic
  @MainThread
  internal fun cancelJob(upload: UploadInfo) {
    assertMainThread()
    uploadsByFilename[upload.file.absolutePath]?.let {
      cancelJobInner(it)
      observerJobsByFilename.remove(upload.file.absolutePath)?.cancel()
      uploadsByFilename -= it.file.absolutePath
      forgetUploadState(upload)
    }
  }

  @JvmSynthetic
  @MainThread
  internal fun jobFinished(upload: UploadInfo) {
    assertMainThread()
    observerJobsByFilename.remove(upload.file.absolutePath)?.cancel()
    uploadsByFilename -= upload.file.absolutePath
    forgetUploadState(upload)
  }

  private fun cancelJobInner(upload: UploadInfo) {
    upload.uploadJob?.cancel()
  }

  private fun insertOrUpdateUpload(upload: UploadInfo, restart: Boolean): UploadInfo {
    val filename = upload.file.absolutePath
    var newUpload = uploadsByFilename[filename]
    // Use the old job if possible (unless requested otherwise)
    if (newUpload?.uploadJob == null) {
      newUpload = startUploadJob(upload)
    } else {
      if (restart) {
        cancelJobInner(upload)
        newUpload = startUploadJob(upload)
      }
    }
    uploadsByFilename += upload.file.absolutePath to newUpload
    observerJobsByFilename[upload.file.absolutePath]?.cancel()
    observerJobsByFilename += upload.file.absolutePath to newObserveProgressJob(newUpload)
    return newUpload
  }

  private fun newObserveProgressJob(upload: UploadInfo): Job {
    // This job has up to three children, one for each of the state flows on UploadInfo
    return mainScope.launch {
      upload.progressChannel?.let { flow ->
        launch {
          flow.collect { state ->
            launch(Dispatchers.IO) { writeUploadState(upload, state) }
          }
        }
      }

      val terminatingCollector: FlowCollector<Any> = FlowCollector { jobFinished(upload) }
      upload.errorChannel?.let { flow -> launch { flow.collect(terminatingCollector) } }
      upload.successChannel?.let { flow -> launch { flow.collect(terminatingCollector) } }
    }
  }

}
