package com.mux.video.upload.internal

import android.net.Uri
import com.mux.video.upload.api.HdrHandling
import com.mux.video.upload.api.UploadStatus
import com.mux.video.upload.api.MuxUpload
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import java.io.File

@Suppress("unused")
enum class MaximumResolution(val width: Int, val height: Int) {
  /**
   * By default the standardized input will be
   * scaled down to 1920x1080 (1080p) from a larger
   * size. Inputs with smaller dimensions won't be
   * scaled up.
   */
  Default(1920, 1080),

  /**
   * The standardized input will be scaled down
   * to 1280x720 (720p) from a larger size. Inputs
   * with smaller dimensions won't be scaled up.
   */
  Preset1280x720(1280, 720),  // 720p

  /**
   * The standardized input will be scaled down
   * to 1920x1080 (1080p) from a larger size. Inputs
   * with smaller dimensions won't be scaled up.
   */
  Preset1920x1080(1920, 1080), // 1080p

  /**
   * Requested maximum generated dimensions of 3840x2160 (2160p/4K), without upscaling.
   * If safe local conversion is unavailable, the original file is uploaded.
   * Configure the matching Direct Upload asset tier separately.
   */
  Preset3840x2160(3840, 2160), // 2160p

  /**
   * Requested maximum generated dimensions of 2560x1440 (1440p), without upscaling.
   * If safe local conversion is unavailable, the original file is uploaded.
   * Configure the matching Direct Upload asset tier separately.
   */
  Preset2560x1440(2560, 1440) // 1440p
}

data class InputStandardization(
  @JvmSynthetic internal val standardizationRequested: Boolean,
  @JvmSynthetic internal val maximumResolution: MaximumResolution,
  @JvmSynthetic internal val hdrHandling: HdrHandling,
) {
  // Keep the original constructor and its Kotlin default-argument JVM signature.
  @JvmOverloads
  constructor(
    standardizationRequested: Boolean = true,
    maximumResolution: MaximumResolution = MaximumResolution.Default,
  ) : this(standardizationRequested, maximumResolution, HdrHandling.Preserve)

  // The data class's original copy and copy$default are callable from compiled Kotlin clients.
  fun copy(
    standardizationRequested: Boolean = this.standardizationRequested,
    maximumResolution: MaximumResolution = this.maximumResolution,
  ): InputStandardization = InputStandardization(
    standardizationRequested, maximumResolution, hdrHandling,
  )
}

/**
 * This object is the SDK's internal representation of an upload that is in-progress. The public
 * object is [MuxUpload], which is backed by an instance of this object.
 *
 * This object is immutable. To create an updated version use [update]. The Upload Manager can
 * update the internal state of its jobs based on the content of this object
 *
 * To create a new upload job, use [UploadJobFactory.create]. The UploadInfo returned will have a
 * Job and Flows populated
 */
internal data class UploadInfo(
  @JvmSynthetic internal val inputStandardization: InputStandardization = InputStandardization(),
  @JvmSynthetic internal val remoteUri: Uri,
  @JvmSynthetic internal val inputFile: File,
  @JvmSynthetic internal val chunkSize: Int,
  @JvmSynthetic internal val retriesPerChunk: Int,
  @JvmSynthetic internal val optOut: Boolean,
  @JvmSynthetic internal val uploadJob: Deferred<Result<UploadStatus>>?,
  @JvmSynthetic internal val statusFlow: StateFlow<UploadStatus>?,
  @JvmSynthetic internal val attempt: UploadAttempt? = null,
  @JvmSynthetic internal val restoredFromOriginal: Boolean = false,
  @JvmSynthetic internal val generatedResumeBlocked: Boolean = false,
) {
  internal var session = UploadSession()

  fun isRunning(): Boolean = attempt?.isStopped() != true &&
    (statusFlow?.value?.let {
      it is UploadStatus.Uploading || it is UploadStatus.Started || it is UploadStatus.Preparing
    } ?: false)
  fun isStandardizationRequested(): Boolean = inputStandardization.standardizationRequested
}

/**
 * Return a new [UploadInfo] with the given data overwritten. Any argument not provided will be
 * copied from the original object.
 */
@JvmSynthetic
internal fun UploadInfo.update(
  inputStandardization: InputStandardization = this.inputStandardization,
  remoteUri: Uri = this.remoteUri,
  file: File = this.inputFile,
  chunkSize: Int = this.chunkSize,
  retriesPerChunk: Int = this.retriesPerChunk,
  optOut: Boolean = this.optOut,
  uploadJob: Deferred<Result<UploadStatus>>? = this.uploadJob,
  statusFlow: StateFlow<UploadStatus>? = this.statusFlow,
  attempt: UploadAttempt? = this.attempt,
  restoredFromOriginal: Boolean = this.restoredFromOriginal,
  generatedResumeBlocked: Boolean = this.generatedResumeBlocked,
) = UploadInfo(
  inputStandardization,
  remoteUri,
  file,
  chunkSize,
  retriesPerChunk,
  optOut,
  uploadJob,
  statusFlow,
  attempt,
  restoredFromOriginal,
  generatedResumeBlocked,
).also { it.session = session }
