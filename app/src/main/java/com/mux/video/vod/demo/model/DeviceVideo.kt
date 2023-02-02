package com.mux.video.vod.demo.model

import android.net.Uri
import java.io.File

/**
 * Represents a video saved on the device
 */
data class DeviceVideo (
  val contentUri: Uri,
  val file: File,
  // TODO: like duration and stuff
  )