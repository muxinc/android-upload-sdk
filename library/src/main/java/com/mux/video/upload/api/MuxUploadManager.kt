package com.mux.video.upload.api

import android.content.Context
import androidx.annotation.MainThread
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.internal.*
import kotlinx.coroutines.*
import java.io.File

/**
 * Manages in-process uploads, allowing them to be observed from anywhere or restarted in case of
 * network loss or process death
 *
 * To list all unfinished jobs, use [allUploadJobs]
 *
 * To find a job associated with a given file, use [findUploadByFile]
 *
 * To restart all uploads after process or network death, use [resumeAllCachedJobs].
 *
 * @see MuxUpload
 */
object MuxUploadManager {

  public  var appContext: Context? = null;
  private val mainScope = MainScope()

  private val uploadsByFilename: MutableMap<String, UploadInfo> = mutableMapOf()
  private val observerJobsByFilename: MutableMap<String, Job> = mutableMapOf()
  private val listeners: MutableSet<UploadEventListener<List<MuxUpload>>> = mutableSetOf()
  private val logger get() = MuxUploadSdk.logger

  /**
   * Finds an in-progress, paused, or failed upload and returns a [MuxUpload] to track it, if it was
   * in progress
   */
  @Suppress("unused")
  @MainThread
  fun findUploadByFile(videoFile: File): MuxUpload? =
    uploadsByFilename[videoFile.absolutePath]?.let { MuxUpload.create(it) }

  /**
   * Finds all in-progress or paused uploads and returns [MuxUpload] objects representing them. You
   * don't need to hold these specific instances except where they're locally used. The upload jobs
   * will continue in parallel with the rest of your app
   */
  @Suppress("unused")
  @MainThread
  fun allUploadJobs(): List<MuxUpload> = uploadsByFilename.values.map { MuxUpload.create(it) }

  /**
   * Resumes any upload jobs that were prematurely stopped due to failures or process death.
   * The jobs will all be resumed where they left off. Any uploads resumed this way will be returned
   */
  @MainThread
  fun resumeAllCachedJobs(): List<MuxUpload> {
    return readAllCachedUploads()
      .onEach { uploadInfo -> startJob(uploadInfo, restart = false) }
      .map { MuxUpload.create(it) }
  }

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
    uploadsByFilename[upload.inputFile.absolutePath]?.let {
      cancelJobInner(it)
      val pausedUpload = upload.update(
        uploadJob = null,
        progressFlow = null,
        errorFlow = null,
        successFlow = null,
      )
      uploadsByFilename[pausedUpload.inputFile.absolutePath] = pausedUpload
      return pausedUpload
    }
    notifyListListeners()
    return upload
  }

  @JvmSynthetic
  @MainThread
  internal fun cancelJob(upload: UploadInfo) {
    assertMainThread()
    uploadsByFilename[upload.inputFile.absolutePath]?.let {
      observerJobsByFilename.remove(upload.inputFile.absolutePath)?.cancel()
      cancelJobInner(it)
      uploadsByFilename -= it.inputFile.absolutePath
      forgetUploadState(upload)
    }
    notifyListListeners()
  }

  @JvmSynthetic
  @MainThread
  internal fun jobFinished(upload: UploadInfo, forgetJob: Boolean = true) {
    assertMainThread()
    observerJobsByFilename.remove(upload.inputFile.absolutePath)?.cancel()
    uploadsByFilename -= upload.inputFile.absolutePath
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
    val filename = upload.inputFile.absolutePath
    var newUpload = uploadsByFilename[filename]
    // Use the old job if possible (unless requested otherwise)
    if (newUpload?.uploadJob == null) {
      if (restart) {
        forgetUploadState(upload)
      }
      newUpload = startUploadJob(upload)
    } else {
      if (restart) {
        cancelJob(upload)
        newUpload = startUploadJob(upload)
      }
    }
    uploadsByFilename += upload.inputFile.absolutePath to newUpload
    observerJobsByFilename[upload.inputFile.absolutePath]?.cancel()
    observerJobsByFilename += upload.inputFile.absolutePath to newObserveProgressJob(newUpload)
    return newUpload
  }

  private fun newObserveProgressJob(upload: UploadInfo): Job {
    // This job has up to three children, one for each of the state flows on UploadInfo
    return mainScope.launch {
      upload.successFlow?.let { flow -> launch { flow.collect { jobFinished(upload) } } }
    }
  }

}
