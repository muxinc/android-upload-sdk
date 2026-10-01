package com.mux.video.upload.internal

import android.net.Uri
import com.mux.exoplayeradapter.AbsRobolectricTest
import com.mux.video.upload.api.HdrHandling
import com.mux.video.upload.api.MuxUpload
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StandardInputOptionsTests : AbsRobolectricTest() {
  @Test
  fun defaultsAndExistingConstructors() {
    assertEquals(InputStandardization(true, MaximumResolution.Default), InputStandardization())
    assertEquals(HdrHandling.Preserve, InputStandardization(false).hdrHandling)
    assertEquals(HdrHandling.Preserve,
      InputStandardization(maximumResolution = MaximumResolution.Preset3840x2160).hdrHandling)
    assertEquals(InputStandardization(), info(MuxUpload.Builder("https://example.invalid/upload", File("input")).build()).inputStandardization)
  }

  @Test
  fun resolutionDimensionsAndExistingOrdinals() {
    assertEquals(2560, MaximumResolution.Preset2560x1440.width)
    assertEquals(1440, MaximumResolution.Preset2560x1440.height)
    assertEquals(3, MaximumResolution.Preset3840x2160.ordinal)
    assertEquals(1920, MaximumResolution.Default.width)
    assertEquals(1080, MaximumResolution.Default.height)
  }

  @Test
  fun featureSettersRetainOptionsInEitherOrder() {
    for (resolution in MaximumResolution.entries) {
      for (handling in HdrHandling.entries) {
        val builder = MuxUpload.Builder(Uri.parse("https://example.invalid/upload"), File("input"))
        builder.hdrHandling(handling).standardizationRequested(true, resolution)
        val enabled = builder.build()
        builder.standardizationRequested(false)
        assertEquals(InputStandardization(true, resolution, handling), info(enabled).inputStandardization)
        assertEquals(InputStandardization(false, resolution, handling), info(builder.build()).inputStandardization)
        builder.standardizationRequested(true).hdrHandling(handling)
        assertEquals(InputStandardization(true, resolution, handling), info(builder.build()).inputStandardization)
      }
    }
  }

  @Test
  fun updatesAndCopiesRetainHdr() {
    val options = InputStandardization(false, MaximumResolution.Preset2560x1440, HdrHandling.ToneMapToSDR)
    val original = info(MuxUpload.Builder("https://example.invalid/upload", File("input"))
      .standardizationRequested(false, MaximumResolution.Preset2560x1440)
      .hdrHandling(HdrHandling.ToneMapToSDR).build())
    assertEquals(options, original.update(chunkSize = 42).inputStandardization)
    assertEquals(options, options.copy())
    assertEquals(HdrHandling.ToneMapToSDR, options.copy(standardizationRequested = true).hdrHandling)
    assertEquals(HdrHandling.ToneMapToSDR, options.copy(true, MaximumResolution.Default).hdrHandling)
    assertNotEquals(options, options.copy(hdrHandling = HdrHandling.Preserve))
  }

  @Test
  fun featureOptionsDoNotChangeTransportOrAnalyticsSettings() {
    val builder = MuxUpload.Builder("https://example.invalid/upload", File("input"))
    val original = info(builder.build())
    val result = info(builder.standardizationRequested(false, MaximumResolution.Preset2560x1440)
      .hdrHandling(HdrHandling.ToneMapToSDR).build())
    assertEquals(original.chunkSize, result.chunkSize)
    assertEquals(original.retriesPerChunk, result.retriesPerChunk)
    assertEquals(original.optOut, result.optOut)
    assertEquals(InputStandardization(false, MaximumResolution.Preset2560x1440, HdrHandling.ToneMapToSDR), result.inputStandardization)
  }

  private fun info(upload: MuxUpload): UploadInfo = MuxUpload::class.java
    .getDeclaredField("uploadInfo").apply { isAccessible = true }.get(upload) as UploadInfo
}
