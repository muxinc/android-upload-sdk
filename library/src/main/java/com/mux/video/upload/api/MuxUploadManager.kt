package com.mux.video.upload.api

import androidx.annotation.MainThread
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.internal.*
import kotlinx.coroutines.*
import java.io.File

object MuxUploadManager {

  private val mainScope = MainScope()

  private val uploadsByFilename: MutableMap<String, UploadInfo> = mutableMapOf()
  private val observerJobsByFilename: MutableMap<String, Job> = mutableMapOf()
  private val listeners: MutableSet<UploadEventListener<List<MuxUpload>>> = mutableSetOf()
  private val logger get() = MuxUploadSdk.logger

  /**
   * Finds an in-progress or paused upload and returns an object to track it, if it was
   * in progress
   */
  @Suppress("unused")
  fun findUploadByFile(videoFile: File): MuxUpload? =
    uploadsByFilename[videoFile.absolutePath]?.let { MuxUpload.create(it) }

  /**
   * Finds all in-progress or paused uploads and returns [MuxUpload] objects representing them. You
   * don't need to hold these specific instances except where they're locally used. The upload jobs
   * will continue in parallel if they're auto-managed (see [MuxUpload.Builder.manageUploadTask])
   */
  @Suppress("unused")
  fun allUploadJobs(): List<MuxUpload> = uploadsByFilename.values.map { MuxUpload.create(it) }

  /**
   * Adds an [UploadEventListener] for updates to the upload list
   */
  @MainThread
  @Suppress("unused")
  fun addUploadsUpdatedListener(listener: UploadEventListener<List<MuxUpload>>) {
    listeners.add(listener)
    listener.onEvent(uploadsByFilename.values.map { MuxUpload.create(it) })
  }

  /**
   * Removes a previously-added [UploadEventListener] for updates to the upload list
   */
  @MainThread
  @Suppress("unused")
  fun removeUploadsUpdatedListener(listener: UploadEventListener<List<MuxUpload>>) {
    listeners.remove(listener)
  }

  /**
   * Adds a new job to this manager.
   * If it's not started, it will be started
   * If it is started, it will be restarted with new parameters
   */
  @JvmSynthetic
  @MainThread
  internal fun startJob(upload: UploadInfo, restart: Boolean = false): UploadInfo {
    assertMainThread()
    val updatedInfo = insertOrUpdateUpload(upload, restart)
    notifyListListeners()
    return updatedInfo
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
        progressFlow = null,
        errorFlow = null,
        successFlow = null,
      )
      uploadsByFilename[pausedUpload.file.absolutePath] = pausedUpload
      return pausedUpload
    }
    notifyListListeners()
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
    notifyListListeners()
  }

  @JvmSynthetic
  @MainThread
  internal fun jobFinished(upload: UploadInfo, forgetJob: Boolean = true) {
    assertMainThread()
    observerJobsByFilename.remove(upload.file.absolutePath)?.cancel()
    uploadsByFilename -= upload.file.absolutePath
    if (forgetJob) {
      forgetUploadState(upload)
    }
    notifyListListeners()
  }

  private fun notifyListListeners() {
    mainScope.launch {
      val uploads = uploadsByFilename.values.map { MuxUpload.create(it) }
      listeners.forEach { it.onEvent(uploads) }
    }
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
        cancelJob(upload)
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
      upload.progressFlow?.let { flow ->
        launch {
          flow.collect { state ->
            launch(Dispatchers.IO) { writeUploadState(upload, state) }
          }
        }
      }

      upload.errorFlow?.let { flow ->
        launch {
          flow.collect {
            jobFinished(upload, it !is CancellationException)
          }
        }
      }
      upload.successFlow?.let { flow -> launch { flow.collect { jobFinished(upload) } } }
    }
  }

}
