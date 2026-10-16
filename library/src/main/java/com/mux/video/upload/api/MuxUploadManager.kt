package com.mux.video.upload.api

import android.content.Context
import androidx.annotation.MainThread
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUploadManager.allUploadJobs
import com.mux.video.upload.api.MuxUploadManager.findUploadByFile
import com.mux.video.upload.api.MuxUploadManager.resumeAllCachedJobs
import com.mux.video.upload.internal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
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

  private val mainScope = MainScope()
  private val uploadsByFilename: MutableMap<String, UploadInfo> = mutableMapOf()
  private val observerJobsByFilename: MutableMap<String, Job> = mutableMapOf()
  private val listeners: MutableSet<UploadEventListener<List<MuxUpload>>> = mutableSetOf()
  @Suppress("unused")
  private val logger by MuxUploadSdk::logger

  @JvmSynthetic
  internal var appContext: Context? = null

  /**
   * Finds an in-progress, paused, or failed upload and returns a [MuxUpload] to track it, if it was
   * in progress
   */
  @Suppress("unused")
  @MainThread
  fun findUploadByFile(videoFile: File): MuxUpload? =
    uploadsByFilename[videoFile.absolutePath]?.let { MuxUpload.create(it) }

  /**
   * Finds all in-progress, paused, or failed uploads and returns [MuxUpload] objects representing them. You
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
  fun resumeAllCachedJobs(): List<MuxUpload> = resumeCachedJobs(includePaused = true)

  @JvmSynthetic
  @MainThread
  internal fun resumeCachedJobs(includePaused: Boolean): List<MuxUpload> {
    val restored = readCachedUploadSnapshots().filter { cached ->
      cached.upload.inputFile.exists().also { if (!it) forgetUploadState(cached.upload) }
    }.map { (upload, saved) ->
      if (!includePaused && saved.paused) {
        uploadsByFilename.getOrPut(upload.inputFile.absolutePath) {
          val attempt = createUploadAttempt(upload, saved)
          attempt.pause {}
          upload.update(attempt = attempt, statusFlow = attempt.status).also { it.session.current.value = it }
        }
      } else startJob(upload, restart = false, resumeState = saved)
    }
    notifyListListeners()
    return restored.map { MuxUpload.create(it) }
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
  internal fun startJob(upload: UploadInfo, restart: Boolean = false, resumeState: UploadResumeState? = null): UploadInfo {
    assertMainThread()
    val updatedInfo = insertOrUpdateUpload(upload, restart, resumeState)
    notifyListListeners()
    return updatedInfo
  }

  @JvmSynthetic
  @MainThread
  internal fun pauseJob(upload: UploadInfo): UploadInfo {
    assertMainThread()
    // Paused jobs stay in the manager and remain persisted
    uploadsByFilename[upload.inputFile.absolutePath]?.takeIf { it.session === upload.session }?.let {
      it.attempt?.pause { state -> writeUploadState(it, state) }
      cancelJobInner(it)
      notifyListListeners()
      return it
    }
    notifyListListeners()
    return upload
  }

  @JvmSynthetic
  @MainThread
  internal fun cancelJob(upload: UploadInfo) {
    assertMainThread()
    val current = uploadsByFilename[upload.inputFile.absolutePath]
    if (current != null && current.session !== upload.session) {
      // An old destination's handle must not stop or forget its replacement.
      upload.attempt?.cancel {}
      upload.uploadJob?.cancel()
      return
    }
    val cancelled = current ?: upload
    observerJobsByFilename.remove(cancelled.inputFile.absolutePath)?.cancel()
    cancelAttempt(cancelled)
    uploadsByFilename -= cancelled.inputFile.absolutePath
    notifyListListeners()
  }

  @JvmSynthetic
  @MainThread
  internal fun jobFinished(upload: UploadInfo, forgetJob: Boolean = true) {
    assertMainThread()
    // Persistence belongs to the attempt, even when it was never registered with the manager.
    if (forgetJob) forgetUploadState(upload)
    if (uploadsByFilename[upload.inputFile.absolutePath]?.attempt !== upload.attempt) return
    observerJobsByFilename.remove(upload.inputFile.absolutePath)?.cancel()
    // Keep failed jobs discoverable so existing handles follow a manager-driven retry.
    if (forgetJob || upload.statusFlow?.value !is UploadStatus.UploadFailed)
      uploadsByFilename -= upload.inputFile.absolutePath
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

  private fun cancelAttempt(upload: UploadInfo, forgetUnstarted: Boolean = false, forRestart: Boolean = false) {
    val attempt = upload.attempt
    if (attempt != null) attempt.cancel(forRestart = forRestart) { forgetUploadState(upload) }
    else if (forgetUnstarted) forgetUploadState(upload)
    upload.uploadJob?.cancel()
  }

  private fun insertOrUpdateUpload(upload: UploadInfo, restart: Boolean, resumeState: UploadResumeState?): UploadInfo {
    val filename = upload.inputFile.absolutePath
    val previous = uploadsByFilename[filename]
    val newDestination = previous != null && previous.remoteUri != upload.remoteUri
    if (!restart && !newDestination && previous?.uploadJob?.isActive == true && previous.attempt?.isStopped() != true)
      return previous
    var source = previous ?: upload
    if (restart || newDestination) {
      val generatedMayExistRemotely = !newDestination && (source.generatedResumeBlocked ||
        source.attempt?.preparation?.generatedRequestStarted == true || readUploadResumeState(upload).generatedResumeBlocked)
      if (newDestination) {
        source.attempt?.replace { forgetUploadState(source) }
        source.uploadJob?.cancel()
      } else cancelAttempt(source, forgetUnstarted = true, forRestart = true)
      source = upload.update(attempt = null, uploadJob = source.uploadJob,
        statusFlow = null, restoredFromOriginal = false, generatedResumeBlocked = generatedMayExistRemotely).also {
        it.session = if (newDestination) upload.session else source.session
      }
    }
    val newUpload = startUploadJob(source, resumeState.takeIf { previous == null && !restart })
    uploadsByFilename += upload.inputFile.absolutePath to newUpload
    observerJobsByFilename[upload.inputFile.absolutePath]?.cancel()
    observerJobsByFilename += upload.inputFile.absolutePath to newObserveProgressJob(newUpload)
    return newUpload
  }

  private fun newObserveProgressJob(upload: UploadInfo): Job {
    // Clear finished uploads from cache and storage
    return mainScope.launch {
      upload.statusFlow?.let { statusFlow ->
        launch {
          statusFlow
            .filter { it is UploadStatus.UploadSuccess }
            .collect { jobFinished(upload) }
        }
      }
    }
  }
}
