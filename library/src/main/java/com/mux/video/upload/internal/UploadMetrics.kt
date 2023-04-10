package com.mux.video.upload.internal

internal class UploadMetrics private constructor() {

 private var currentEvent: UploadEvent? = null


  fun reportUpload() {
    currentEvent = UploadEvent()

  }
  companion object {
    @JvmSynthetic
    internal fun create() = UploadMetrics()
  }

}

private class UploadEvent() {

}

