package com.mux.video.upload.api

import android.net.Uri
import androidx.annotation.MainThread
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload.Builder
import com.mux.video.upload.internal.UploadInfo
import com.mux.video.upload.internal.update
import kotlinx.coroutines.*
import java.io.File

/**
 * Represents an upload of a video as a Mux Video asset. In order to use this SDK, you must first
 * create a [direct upload](https://docs.mux.com/guides/video/upload-files-directly) server-side,
 * then return that direct upload PUT URL to your app.
 *
 * Once you have a PUT URL, you can create and [start] your upload using the [Builder]
 *
 * For example:
 * ```
 * // Start a new upload
 * val upload = MuxUpload.Builder(myUploadUrl, myInputFile).build()
 * upload.setResultListener { myHandleResult(it) }
 * upload.setProgressListener { myHandleProgress(it) }
 * upload.start()
 * ```
 *
 * For full documentation on how to configure your upload, see the [Builder]
 *
 * @see Builder
 * @see MuxUploadManager
 */
class MuxUpload private constructor(
  private var uploadInfo: UploadInfo, private val autoManage: Boolean = true
) {

  /**
   * File containing the video to be uploaded
   */
  val videoFile: File get() = uploadInfo.inputFile

  /**
   * The current state of the upload. To be notified of state updates, you can use
   * [setProgressListener] and [setResultListener]
   */
  val currentState: Progress
    get() = lastKnownState ?: uploadInfo.progressFlow?.replayCache?.firstOrNull() ?: Progress(
      totalBytes = videoFile.length()
    )

  /**
   * True when the upload is running, false if it's paused, failed, or canceled
   */
  val isRunning get() = uploadInfo.isRunning()

  /**
   * If the upload has failed, gets the error associated with the failure
   */
  val error get() = _error ?: uploadInfo.errorFlow?.replayCache?.firstOrNull()
  private var _error: Exception? = null

  /**
   * True if the upload was successful, false otherwise
   */
  val isSuccessful get() = _successful
  private var _successful: Boolean = false

  private var resultListener: UploadEventListener<Result<Progress>>? = null
  private var progressListener: UploadEventListener<Progress>? = null
  private var observerJob: Job? = null
  private var lastKnownState: Progress? = null

  private val callbackScope: CoroutineScope = MainScope()
  private val logger get() = MuxUploadSdk.logger

  init {
    // Catch Events if an upload was already in progress
    maybeObserveUpload(uploadInfo)
  }

  /**
   * Starts this Upload. You don't need to hold onto this object in order for the upload to
   * complete, it will continue in parallel with the rest of your app. You can always get a handle
   * to an ongoing upload by using [MuxUploadManager.findUploadByFile]
   *
   * To suspend the execution of the upload, use [pause]. To cancel it completely, use [cancel]
   *
   * @param forceRestart Start the upload from the beginning even if the file is partially uploaded
   *
   * @see pause
   * @see cancel
   * @see MuxUploadManager
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
      /*uploadInfo =*/ MuxUploadManager.startJob(uploadInfo, forceRestart)
    } else {
      // If we're not managing the worker, the job is purely internal to this object
      /*uploadInfo =*/ MuxUploadSdk.uploadJobFactory().createUploadJob(uploadInfo, coroutineScope)
    }

    logger.i("MuxUpload", "started upload: ${uploadInfo.inputFile}")
    maybeObserveUpload(uploadInfo)
  }

  /**
   * If the upload has not succeeded, this function will suspend until the upload completes and
   * return the result
   *
   * If the upload had failed, it will be restarted and this function will suspend until it
   * completes
   *
   * If the upload already succeeded, the old result will be returned immediately
   */
  @Throws
  @Suppress("unused")
  @JvmSynthetic
  suspend fun awaitSuccess(): Result<Progress> {
    val result = uploadInfo.successFlow?.replayCache?.firstOrNull()
    return if (result != null) {
      Result.success(result) // If we succeeded already, don't start again
    } else {
      coroutineScope {
        startInner(coroutineScope = this)
        uploadInfo.uploadJob?.let { job ->
          val result = job.await()
          result
        } ?: Result.failure(Exception("Upload failed to start"))
      }
    }
  }

  /**
   * Pauses the upload. If the upload was already paused, this method has no effect
   *
   * You can resume the upload where it left off by calling [start]
   */
  @Suppress("MemberVisibilityCanBePrivate")
  fun pause() {
    uploadInfo = if (autoManage) {
      observerJob?.cancel("user requested pause")
      /*uploadInfo =*/ MuxUploadManager.pauseJob(uploadInfo)
    } else {
      observerJob?.cancel("user requested pause")
      uploadInfo.uploadJob?.cancel()
      /*uploadInfo =*/ uploadInfo.update(
        uploadJob = null,
        successFlow = null,
        errorFlow = null,
        progressFlow = null,
      )
    }
    lastKnownState?.let { state -> progressListener?.onEvent(state) }
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

  /**
   * Sets a listener for progress updates on this upload
   */
  @MainThread
  fun setProgressListener(listener: UploadEventListener<Progress>?) {
    progressListener = listener
    lastKnownState?.let { listener?.onEvent(it) }
  }

  /**
   * Sets a listener for success or failure updates on this upload
   */
  @MainThread
  fun setResultListener(listener: UploadEventListener<Result<Progress>>) {
    resultListener = listener
    lastKnownState?.let {
      if (it.bytesUploaded >= it.totalBytes) {
        listener.onEvent(Result.success(it))
      }
    }
  }

  /**
   * Clears all listeners set on this object
   */
  @MainThread
  fun clearListeners() {
    resultListener = null
    progressListener = null
  }

  private fun newObserveProgressJob(upload: UploadInfo): Job {
    // This job has up to three children, one for each of the state flows on UploadInfo
    return callbackScope.launch {
      upload.errorFlow?.let { flow ->
        launch {
          flow.collect { error ->
            if (error !is CancellationException) { // Canceled uploads shouldn't generate events
              _error = error
              resultListener?.onEvent(Result.failure(error))
            }
          }
        }
      }
      upload.successFlow?.let { flow ->
        launch {
          flow.collect { state ->
            lastKnownState = state
            _successful = true
            resultListener?.onEvent(Result.success(state))
          }
        }
      }
      upload.progressFlow?.let { flow ->
        launch {
          flow.collect { state ->
            lastKnownState = state
            progressListener?.onEvent(state)
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
   * The current progress of an upload, in terms of time elapsed and data transmitted
   */
  data class Progress(
    val bytesUploaded: Long = 0,
    val totalBytes: Long = 0,
    val startTime: Long = 0,
    val updatedTime: Long = 0,
  )

  /**
   * Builds instances of [MuxUpload].
   *
   * If you wish for fine-grained control over the upload process, some configuration is available.
   *
   * For example:
   * ```
   * // Adapt to your upload to current network conditions
   * val chunkSize = if (/* onWifi */) {
   *   16 * 1024 * 1024 // 16M, bigger chunks go faster
   * } else {
   *   8 * 1024 * 1024 // 8M, smaller chunks are more reliable
   * }
   *
   * val upload = MuxUpload.Builder(myUploadUrl, myInputFile)
   *   .chunkSize(chunkSize) // Mux's default is 8Mb
   *   .retriesPerChunk(5) // Mux's default is 3
   *   .build()
   * ```
   *
   * @param uploadUri the URL obtained from the Direct video up
   * @param videoFile a File that represents the video file you want to upload
   */
  @Suppress("MemberVisibilityCanBePrivate")
  class Builder constructor(val uploadUri: Uri, val videoFile: File) {

    /**
     * Create a new Builder with the specified input file and upload URL
     *
     * @param uploadUri the URL obtained from the Direct video up
     * @param videoFile a File that represents the video file you want to upload
     */
    @Suppress("unused")
    constructor(uploadUri: String, videoFile: File)
            : this(Uri.parse(uploadUri), videoFile)

    private var manageTask: Boolean = true
    private var uploadInfo: UploadInfo = UploadInfo(
      // Default values
      remoteUri = uploadUri,
      inputFile = videoFile,
      chunkSize = 8 * 1024 * 1024, // GCP recommends at least 8M chunk size
      retriesPerChunk = 3,
      standardizeRequested = true,
      optOut = false,
      uploadJob = null,
      successFlow = null,
      progressFlow = null,
      errorFlow = null
    )
    /**
     * Allow Mux to manage and remember the state of this upload
     */
    @Suppress("unused")
    fun manageUploadTask(autoManage: Boolean): Builder {
      manageTask = autoManage;
      return this
    }

    /**
     * If requested, the Upload SDK will try to standardize the input file in order to optimize it
     * for use with Mux Video
     */
    @Suppress("unused")
    fun standardizeRequested(enabled: Boolean) {
      uploadInfo.update(standardizeRequested = enabled)
    }

    /**
     * The Upload SDK will upload your file in smaller chunks, which can be more reliable in adverse
     * network conditions.
     *
     * @param sizeBytes The chunk size in bytes. Mux's default is 8M
     */
    @Suppress("unused")
    fun chunkSize(sizeBytes: Int): Builder {
      uploadInfo.update(chunkSize = sizeBytes)
      return this
    }

    /**
     * Allows you to opt out of Mux's performance analytics tracking. We track metrics related to
     * the overall performance and reliability of your upload, in order to make our SDK better.
     *
     * If you would perfer not to share this information with us, you may opt out by passing `true`
     * here.
     */
    @Suppress("unused")
    fun optOutOfEventTracking(optOut: Boolean) {
      uploadInfo.update(optOut = optOut)
    }

    /**
     * The Upload SDK will upload your file in smaller chunks, which can be more reliable in adverse
     * network conditions. Each chunk can be retried individually, up to the given number of times
     *
     * @param retries The number of retries per chunk. Mux's default is 3
     */
    @Suppress("unused")
    fun retriesPerChunk(retries: Int): Builder {
      uploadInfo.update(retriesPerChunk = retries)
      return this
    }

    /**
     * Creates a new [MuxUpload] with the given configuration.
     */
    fun build() = MuxUpload(uploadInfo)
  }

  internal companion object {
    @JvmSynthetic
    internal fun create(uploadInfo: UploadInfo) = MuxUpload(uploadInfo)
  }
}
