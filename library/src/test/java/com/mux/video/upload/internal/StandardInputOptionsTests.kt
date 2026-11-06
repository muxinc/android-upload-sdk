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
      .chunkSize(256 * 1024).retriesPerChunk(7).optOutOfEventTracking(true)
    val original = info(builder.build())
    assertEquals(256 * 1024, original.chunkSize)
    assertEquals(7, original.retriesPerChunk)
    assertTrue(original.optOut)
    val result = info(builder.standardizationRequested(false, MaximumResolution.Preset2560x1440)
      .hdrHandling(HdrHandling.ToneMapToSDR).build())
    assertEquals(original.chunkSize, result.chunkSize)
    assertEquals(original.retriesPerChunk, result.retriesPerChunk)
    assertEquals(original.optOut, result.optOut)
    assertEquals(InputStandardization(false, MaximumResolution.Preset2560x1440, HdrHandling.ToneMapToSDR), result.inputStandardization)
  }

  @Test
  fun transportAndAnalyticsDefaultsRemainUnchanged() {
    val defaults = info(MuxUpload.Builder("https://example.invalid/upload", File("input")).build())
    assertEquals(8 * 1024 * 1024, defaults.chunkSize)
    assertEquals(3, defaults.retriesPerChunk)
    assertFalse(defaults.optOut)
  }

  @Test
  fun analyticsOptOutCanBeChangedWithoutMutatingAnAlreadyBuiltUpload() {
    val builder = MuxUpload.Builder("https://example.invalid/upload", File("input"))
    val optedOut = builder.optOutOfEventTracking(true).build()
    val optedIn = builder.optOutOfEventTracking(false).build()
    assertTrue(info(optedOut).optOut)
    assertFalse(info(optedIn).optOut)
    // Runtime reads use session.current, so checking only the constructor's field is insufficient.
    assertTrue(info(optedOut).session.current.value!!.optOut)
    assertFalse(info(optedIn).session.current.value!!.optOut)
    assertNotSame(info(optedOut).session, info(optedIn).session)
  }

  @Test
  fun chunkSizesAreNormalizedToPositiveProtocolAlignedValues() {
    val unit = 256 * 1024
    val cases = listOf(Int.MIN_VALUE to unit, -1 to unit, 0 to unit, unit - 1 to unit,
      unit to unit, unit + 1 to unit, 2 * unit + 7 to 2 * unit,
      Int.MAX_VALUE to 2_147_221_504)
    for ((requested, expected) in cases) {
      val upload = MuxUpload.Builder("https://example.invalid/upload", File("input"))
        .chunkSize(requested).build()
      assertEquals("Requested $requested", expected, info(upload).chunkSize)
    }
  }

  @Test
  fun retryBudgetRetainsPositiveValuesAndClampsNegativeValuesToZero() {
    for ((requested, expected) in listOf(Int.MIN_VALUE to 0, -1 to 0, 0 to 0, 7 to 7)) {
      val upload = MuxUpload.Builder("https://example.invalid/upload", File("input"))
        .retriesPerChunk(requested).build()
      assertEquals(expected, info(upload).retriesPerChunk)
    }
  }

  private fun info(upload: MuxUpload): UploadInfo = MuxUpload::class.java
    .getDeclaredField("uploadInfo").apply { isAccessible = true }.get(upload) as UploadInfo
}
