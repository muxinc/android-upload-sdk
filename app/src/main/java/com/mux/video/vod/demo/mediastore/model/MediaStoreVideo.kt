package com.mux.video.vod.demo.mediastore.model

import java.io.File

/**
 * Represents a video saved on the device, accessed via a ContentResolver. To use with the SDK, we
 * need to copy the file to our local storage
 */
data class MediaStoreVideo (
  val title: String,
  val file: File,
  val fromApp: String,
  val date: String,
  )