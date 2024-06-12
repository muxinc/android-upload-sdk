package com.mux.video.upload.internal

import android.net.Uri
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
   *  The standardized input will be scaled down
   *  to 3840x2160 (2160p/4K) from a larger size.
   *  Inputs with smaller dimensions won't be scaled
   *  up.
   */
  Preset3840x2160(3840, 2160) // 2160p
}

data class InputStandardization(
  @JvmSynthetic internal val standardizationRequested: Boolean = true,
  @JvmSynthetic internal val maximumResolution: MaximumResolution = MaximumResolution.Default,
) {

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
  @JvmSynthetic internal val standardizedFile: File? = null,
  @JvmSynthetic internal val chunkSize: Int,
  @JvmSynthetic internal val retriesPerChunk: Int,
  @JvmSynthetic internal val optOut: Boolean,
  @JvmSynthetic internal val uploadJob: Deferred<Result<UploadStatus>>?,
  @JvmSynthetic internal val statusFlow: StateFlow<UploadStatus>?,
) {
  fun isRunning(): Boolean = 
    statusFlow?.value?.let {
      it is UploadStatus.Uploading || it is UploadStatus.Started || it is UploadStatus.Preparing
    } ?: false
  fun isStandardizationRequested(): Boolean = inputStandardization.standardizationRequested
}

/**
 * Return a new [UploadInfo] with the given data overwritten. Any argument not provided will be
 * copied from the original object.
 */
@JvmSynthetic
internal fun UploadInfo.update(
  inputStandardization: InputStandardization = InputStandardization(),
  remoteUri: Uri = this.remoteUri,
  file: File = this.inputFile,
  standardizedFile: File? = this.standardizedFile,
  chunkSize: Int = this.chunkSize,
  retriesPerChunk: Int = this.retriesPerChunk,
  optOut: Boolean = this.optOut,
  uploadJob: Deferred<Result<UploadStatus>>? = this.uploadJob,
  statusFlow: StateFlow<UploadStatus>? = this.statusFlow,
) = UploadInfo(
  inputStandardization,
  remoteUri,
  file,
  standardizedFile,
  chunkSize,
  retriesPerChunk,
  optOut,
  uploadJob,
  statusFlow,
)
