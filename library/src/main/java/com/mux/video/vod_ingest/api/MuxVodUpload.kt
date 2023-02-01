package com.mux.video.vod_ingest.api

import android.net.Uri
import com.mux.video.vod_ingest.MuxVodUploadSdk
import kotlinx.coroutines.Job
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
class MuxVodUpload private constructor(
  private val putUri: Uri,
  private val srcFile: File,
  private val chunkSizeBytes: Int,
  private val retriesPerChunk: Int,
) {

  /**
   * Builds instances of this object
   *
   * @param uploadUri the URL obtained from the Direct video up
   */
  class Builder constructor(val uploadUri: Uri, val videoFile: File) {

    constructor(uploadUri: String, videoFile: File) : this(Uri.parse(uploadUri), videoFile)

    fun chunkSize(sizeBytes: Int) {

    }

    fun retriesPerChunk(retries: Int) {

    }

    fun build() = MuxVodUpload(
      putUri = uploadUri,
      srcFile = videoFile,
      chunkSizeBytes = 0/*TODO*/,
      retriesPerChunk = 0/*TODO*/
    )
  }
}