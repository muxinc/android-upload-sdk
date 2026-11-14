package com.mux.video.upload.api

/**
 * Requested HDR behavior for input standardization.
 *
 * HDR inputs that already meet Mux's standard input requirements upload unchanged with
 * [Preserve]. [ToneMapToSDR] converts supported HLG/PQ inputs to SDR on the device. When the
 * device or input cannot support the requested conversion, the original file is uploaded.
 */
enum class HdrHandling {
  /** Request preservation of eligible HDR. This is the default; HDR playback is not guaranteed. */
  Preserve,

  /** Request explicit HDR-to-SDR tone mapping when the conversion pipeline supports it. */
  ToneMapToSDR,
}
