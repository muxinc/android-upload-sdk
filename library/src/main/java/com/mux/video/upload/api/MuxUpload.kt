package com.mux.video.upload.api

import android.net.Uri
import androidx.annotation.MainThread
import androidx.core.util.Consumer
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.internal.UploadInfo
import com.mux.video.upload.internal.update
import kotlinx.coroutines.*
import java.io.File

/**
 * Represents a task that does a single direct upload to a Mux Video asset previously created.
 *
 * TODO: Talk about creating the upload: https://docs.mux.com/api-reference/video#operation/create-direct-upload
 *
 * This prototype does a single streamed PUT request for the whole file and delivers results, but
 * the production version will have more sophisticated behavior.
 *
 * Create an instance of this class with the [Builder]
 */
class MuxUpload private constructor(
  private var uploadInfo: UploadInfo, private val autoManage: Boolean = true
) {

  val videoFile: File get() = uploadInfo.file
  val currentState: Progress get() = lastKnownState ?: Progress(totalBytes = videoFile.length())
  // TODO: Add more (possibly all) properties read-only from UploadInfo

  private val successConsumers: MutableList<Consumer<Progress>> = mutableListOf()
  private val failureConsumers: MutableList<Consumer<Exception>> = mutableListOf()
  private val progressConsumers: MutableList<Consumer<Progress>> = mutableListOf()
  private var observerJob: Job? = null
  private var lastKnownState: Progress? = null

  private val callbackScope: CoroutineScope = MainScope()
  private val logger get() = MuxUploadSdk.logger

  init {
    // Catch Events if an upload was already in progress
    maybeObserveUpload(uploadInfo)
  }

  /**
   * Starts this Upload. The Upload will continue in the background *even if this object is
   * destroyed*.
   * To suspend the execution of the upload, use [pause]. To cancel it completely, use [cancel]
   *
   * @param forceRestart Start the upload from the beginning even if the file is partially uploaded
   */
  @JvmOverloads
  fun start(forceRestart: Boolean = false) {
    startInner(forceRestart = forceRestart)
  }

  // Starts in the given coroutine scope.
  // Auto-managed jobs do not honor the coroutineScope param, they are always in the UploadManager's
  //   context
  private fun startInner(
    forceRestart: Boolean = false,
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
  ) {
    // Get an updated UploadInfo with a job & event channels
    uploadInfo = if (autoManage) {
      // We may or may not get a fresh worker, depends on if the upload is already going
      MuxUploadManager.startJob(uploadInfo, forceRestart)
    } else {
      // If we're not managing the worker, the job is purely internal to this object
      MuxUploadSdk.uploadJobFactory().createUploadJob(uploadInfo, coroutineScope)
    }

    logger.i("MuxUpload", "started upload: ${uploadInfo.file}")
    maybeObserveUpload(uploadInfo)
  }

  @Throws
  @Suppress("unused")
  suspend fun awaitSuccess(): Progress {
    return coroutineScope {
      startInner(coroutineScope = this)
      uploadInfo.uploadJob?.let { job ->
        val result = job.await()
        result.exceptionOrNull()?.let { throw it }
        result.getOrThrow()
      } ?: Progress(0, uploadInfo.file.length())
    }
  }

  /**
   * Pauses the upload. If the upload was already paused, this method has no effect
   * You can resume the upload where it left off by calling [start]
   */
  @Suppress("MemberVisibilityCanBePrivate")
  fun pause() {
    uploadInfo = if (autoManage) {
      MuxUploadManager.pauseJob(uploadInfo)
    } else {
      uploadInfo.uploadJob?.cancel()
      uploadInfo.update(
        uploadJob = null,
        successChannel = null,
        errorChannel = null,
        progressChannel = null,
      )
    }
    observerJob?.cancel("user requested pause")
  }

  /**
   * Cancels this upload. The upload job will be canceled and it will not be possible to start this
   * job again where it left off
   */
  @Suppress("MemberVisibilityCanBePrivate")
  fun cancel() {
    if (autoManage) {
      MuxUploadManager.cancelJob(uploadInfo)
    } else {
      uploadInfo.uploadJob?.cancel("user requested cancel")
    }
    lastKnownState = null
    observerJob?.cancel("user requested cancel")
  }

  @MainThread
  fun addSuccessConsumer(cb: Consumer<Progress>) {
    successConsumers += cb
  }

  @MainThread
  fun removeSuccessConsumer(cb: Consumer<Progress>) {
    successConsumers -= cb
  }

  @MainThread
  fun addFailureConsumer(cb: Consumer<Exception>) {
    failureConsumers += cb
  }

  @MainThread
  fun removeFailureConsumer(cb: Consumer<Exception>) {
    failureConsumers -= cb
  }

  @MainThread
  fun addProgressConsumer(cb: Consumer<Progress>) {
    progressConsumers += cb
  }

  @MainThread
  fun removeProgressConsumer(cb: Consumer<Progress>) {
    progressConsumers -= cb
  }

  private fun newObserveProgressJob(upload: UploadInfo): Job {
    // This job has up to three children, one for each of the state flows on UploadInfo
    return callbackScope.launch {
      upload.errorChannel?.let { flow ->
        launch { flow.collect { error -> failureConsumers.forEach { it.accept(error) } } }
      }
      upload.successChannel?.let { flow ->
        launch {
          flow.collect { state ->
            lastKnownState = state
            successConsumers.forEach { it.accept(state) }
          }
        }
      }
      upload.progressChannel?.let { flow ->
        launch {
          flow.collect { state ->
            lastKnownState = state
            progressConsumers.forEach { it.accept(state) }
          }
        }
      }
    }
  }

  private fun maybeObserveUpload(uploadInfo: UploadInfo) {
    observerJob?.cancel("switching observers")
    observerJob = newObserveProgressJob(uploadInfo)
  }

  /**
   * The
   */
  data class Progress(
    val bytesUploaded: Long = 0,
    val totalBytes: Long = 0,
    val startTime: Long = 0,
    val updatedTime: Long = 0,
  )

  /**
   * Builds instances of [MuxUpload]
   *
   * @param uploadUri the URL obtained from the Direct video up
   */
  @Suppress("MemberVisibilityCanBePrivate")
  class Builder constructor(val uploadUri: Uri, val videoFile: File) {
    constructor(uploadUri: String, videoFile: File) : this(Uri.parse(uploadUri), videoFile)

    private var manageTask: Boolean = true
    private var uploadInfo: UploadInfo = UploadInfo(
      // Default values
      remoteUri = uploadUri,
      file = videoFile,
      videoMimeType = "video/*",
      chunkSize = 8 * 1024 * 1024, // GCP recommends at least 8M chunk size
      retriesPerChunk = 3,
      retryBaseTimeMs = 500,
      uploadJob = null,
      successChannel = null,
      progressChannel = null,
      errorChannel = null
    )

    @Suppress("unused")
    fun manageUploadTask(autoManage: Boolean): Builder {
      manageTask = autoManage;
      return this
    }

    @Suppress("unused")
    fun chunkSize(sizeBytes: Int): Builder {
      uploadInfo.update(chunkSize = sizeBytes)
      return this
    }

    @Suppress("unused")
    fun retriesPerChunk(retries: Int): Builder {
      uploadInfo.update(retriesPerChunk = retries)
      return this
    }

    @Suppress("unused")
    fun backoffBaseTime(backoffTimeMillis: Long): Builder {
      uploadInfo.update(retryBaseTimeMs = backoffTimeMillis)
      return this
    }

    fun build() = MuxUpload(uploadInfo)
  }

  companion object {
    @JvmSynthetic
    internal fun create(uploadInfo: UploadInfo) = MuxUpload(uploadInfo)
  }
}
