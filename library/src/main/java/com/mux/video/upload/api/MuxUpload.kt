package com.mux.video.upload.api

import android.net.Uri
import androidx.annotation.MainThread
import androidx.core.util.Consumer
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.internal.UploadInfo
import com.mux.video.upload.internal.UploadJobFactory
import com.mux.video.upload.internal.update
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
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
  private var uploadInfo: UploadInfo,
  private val autoManage: Boolean = true
) {

  val videoFile: File get() = uploadInfo.file
  // TODO: Add more (possibly all) properties read-only from UploadInfo

  private val successConsumers: MutableList<Consumer<State>> = mutableListOf()
  private val failureConsumers: MutableList<Consumer<Exception>> = mutableListOf()
  private val progressConsumers: MutableList<Consumer<State>> = mutableListOf()

  private val mainScope: CoroutineScope = MainScope()
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
    // Get an updated UploadInfo with a job & event channels
    // We may or may not get a fresh worker, depends on if the upload is already going
    uploadInfo = if (autoManage) {
      MuxUploadManager.startJob(uploadInfo, forceRestart)
    } else {
      MuxUploadSdk.uploadJobFactory()
        .createUploadJob(uploadInfo, CoroutineScope(Dispatchers.Default))
    }

    logger.i("MuxUpload", "started upload: ${uploadInfo.file}")

    maybeObserveUpload(uploadInfo)
  }

  @Throws
  suspend fun awaitSuccess(): State {
    start()
    return uploadInfo.uploadJob?.let { job ->
      val result = job.await()
      result.exceptionOrNull()?.let { throw it }
      result.getOrThrow()
    } ?: State(0, uploadInfo.file.length())
  }

  fun pause() {
    logger.w("MuxUpload", "pause() is not implemented yet")
  }

  fun cancel() {
    MuxUploadManager.cancelJob(uploadInfo)
    mainScope.cancel("user requested cancel")
  }

  @MainThread
  fun addSuccessConsumer(cb: Consumer<State>) {
    successConsumers += cb
  }

  @MainThread
  fun removeSuccessConsumer(cb: Consumer<State>) {
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
  fun addProgressConsumer(cb: Consumer<State>) {
    progressConsumers += cb
  }

  @MainThread
  fun removeProgressConsumer(cb: Consumer<State>) {
    progressConsumers -= cb
  }

  private fun <T> Channel<T>.forwardEvents(Consumers: List<Consumer<T>>) {
    mainScope.launch { receiveAsFlow().collect { t -> Consumers.forEach { it.accept(t) } } }
  }

  private fun maybeObserveUpload(uploadInfo: UploadInfo) {
    uploadInfo.successChannel?.forwardEvents(successConsumers)
    uploadInfo.progressChannel?.forwardEvents(progressConsumers)
    uploadInfo.errorChannel?.forwardEvents(failureConsumers)
  }

  data class State(
    val bytesUploaded: Long,
    val totalBytes: Long,
  )

  /**
   * Builds instances of this object
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
      videoMimeType = "application/mp4",
      chunkSize = 32 * 1024 * 1024,
      retriesPerChunk = 3,
      retryBaseTimeMs = 500,
      uploadJob = null,
      successChannel = null,
      progressChannel = null,
      errorChannel = null
    )

    fun manageUploadTask(autoManage: Boolean): Builder {
      manageTask = autoManage;
      return this
    }

    fun chunkSize(sizeBytes: Long): Builder {
      uploadInfo.update(chunkSize = sizeBytes)
      return this
    }

    fun retriesPerChunk(retries: Int): Builder {
      uploadInfo.update(retriesPerChunk = retries)
      return this
    }

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
