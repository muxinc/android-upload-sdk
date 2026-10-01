package com.mux.video.upload.api

/** Controls the requested HDR behavior during input standardization. */
enum class HdrHandling {
  /** Preserve eligible HDR by default. Preserved HDR does not guarantee HDR playback. */
  Preserve,

  /**
   * Explicitly request HDR-to-SDR tone mapping. If the device cannot perform the requested
   * operation, the SDK falls back to uploading the original file.
   */
  ToneMapToSDR,
}
