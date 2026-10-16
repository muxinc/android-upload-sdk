package com.mux.video.upload.api

/**
 * Requested HDR behavior for input standardization.
 *
 * Currently retained as configuration for the new Standard Input pipeline. The legacy
 * transcoder does not yet apply this option.
 */
enum class HdrHandling {
  /** Request preservation of eligible HDR. This is the default; HDR playback is not guaranteed. */
  Preserve,

  /** Request explicit HDR-to-SDR tone mapping when the conversion pipeline supports it. */
  ToneMapToSDR,
}
