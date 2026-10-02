package com.mux.video.upload.internal.standardization

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class MediaMetadataFixtureTests {
  @Test fun inspectExternalCatalogAndMeasureMetadata() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val assets = instrumentation.context.assets
    val catalog = JSONObject(assets.open("fixtures/catalog.json").bufferedReader().use { it.readText() })
    val fixtures = catalog.getJSONArray("fixtures")
    val results = JSONArray()
    val directory = instrumentation.targetContext.getExternalFilesDir("metadata-fixtures")!!
    val fixtureId = InstrumentationRegistry.getArguments().getString("fixtureId")
    require(fixtureId != null) { "Use scripts/run-metadata-fixtures.py to stage a verified fixture" }
    run {
      for (i in 0 until fixtures.length()) {
        val fixture = fixtures.getJSONObject(i)
        if (fixture.getString("id") != fixtureId) continue
        val file = File(directory, fixture.getString("canonicalFilename"))
        assertEquals(fixture.getLong("sizeBytes"), file.length())
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
          val buffer = ByteArray(64 * 1024)
          while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        assertEquals(fixture.getString("sha256"), digest.digest().joinToString("") { "%02x".format(it) })
        val inspector = MediaMetadataInspector()
        val initial = inspector.inspect(file)
        val row = JSONObject().put("id", fixture.getString("id")).put("sha256", fixture.getString("sha256"))
        if (initial is MetadataInspectionResult.Success) {
          assertFalse("Expected malformed fixture rejection", fixture.optBoolean("expectedMetadataFailure"))
          val inspection = initial.inspection
          assertDeferredFacts(inspection.facts)
          val expected = fixture.getJSONObject("facts")
          println("METADATA " + fixture.getString("id") + " " + inspection)
          verifyMetadata(fixture, expected, inspection)
          val times = mutableListOf<Long>()
          repeat(10) {
            val repeated = inspector.inspect(file) as MetadataInspectionResult.Success
            assertEquals(inspection.facts, repeated.inspection.facts)
            times.add(repeated.inspection.elapsedNanos)
          }
          times.sort()
          row.put("status", "success").put("initialMs", inspection.elapsedNanos / 1e6)
            .put("medianMs", (times[4] + times[5]) / 2e6).put("p90Ms", times[8] / 1e6)
            .put("maxMs", times.last() / 1e6).put("facts", inspection.facts.toString())
            .put("tracks", JSONArray(inspection.tracks.map { it.toString() }))
            .put("isoTracks", inspection.isoTracks.toString())
        } else {
          row.put("status", initial.toString())
          assertTrue("Required fixture unreadable: " + fixture.getString("id"),
            fixture.optBoolean("expectedMetadataFailure", false) ||
              fixture.getJSONObject("facts").optString("videoCodec") == "prores")
        }
        results.put(row)
        file.delete()
      }
      val report = JSONObject().put("api", Build.VERSION.SDK_INT).put("model", Build.MODEL)
        .put("catalogId", catalog.getString("catalogId")).put("sampleScanPerformed", false)
        .put("iterations", 10).put("results", results)
      File(instrumentation.targetContext.filesDir, "metadata-results.json").writeText(report.toString(2))
    }
  }

  private fun verifyMetadata(fixture: JSONObject, expected: JSONObject, inspection: MediaMetadataInspection) {
    val facts = inspection.facts
    val strict = fixture.getString("id").startsWith("synthetic-standard")
    val expectedCodec = when (expected.optString("videoCodec")) {
      "h264" -> VideoCodec.H264
      "hevc" -> VideoCodec.Hevc
      else -> VideoCodec.Other
    }
    if (facts.videoCodec != MediaFact.Unknown && !fixture.getString("id").contains("dolby-vision"))
      assertEquals(MediaFact.Known(expectedCodec), facts.videoCodec)
    for ((key, value) in listOf("encodedDimensions" to facts.encodedDimensions, "displayDimensions" to facts.displayDimensions)) {
      val dimensions = expected.optJSONArray(key) ?: continue
      if (strict || value != MediaFact.Unknown)
        assertEquals(fixture.getString("id") + " " + key,
          MediaFact.Known(Dimensions(dimensions.getInt(0), dimensions.getInt(1))), value)
    }
    val range = if (fixture.getString("id").contains("dolby-vision")) DynamicRange.DolbyVision
      else when (expected.optString("dynamicRange")) {
      "sdr" -> DynamicRange.Sdr
      "hlg" -> DynamicRange.Hlg
      "pq" -> DynamicRange.Pq
      "dolbyVision" -> DynamicRange.DolbyVision
      else -> null
    }
    if (range != null && (strict || facts.dynamicRange != MediaFact.Unknown))
      assertEquals(fixture.getString("id") + " range", MediaFact.Known(range), facts.dynamicRange)
    if (expected.has("orientationDegrees") && facts.rotationDegrees != MediaFact.Unknown) {
      val degrees = expected.getInt("orientationDegrees")
      assertEquals(MediaFact.Known((degrees % 360 + 360) % 360), facts.rotationDegrees)
    }
    if (fixture.getString("id").contains("dolby-vision"))
      assertEquals(MediaFact.Known(DynamicRange.DolbyVision), facts.dynamicRange)
    if (strict) {
      assertEquals(MediaFact.Known(PixelFormat(expected.getInt("bitDepth"), ChromaSubsampling.Yuv420)), facts.pixelFormat)
      assertEquals(MediaFact.Known(expected.getInt("orientationDegrees")), facts.rotationDegrees)
      val layout = when (expected.getString("audioLayout")) {
        "5.1" -> AudioChannelLayout.FivePointOne
        else -> AudioChannelLayout.Stereo
      }
      assertEquals(MediaFact.Known(listOf(AudioTrack(MediaFact.Known(AudioFormat.Aac(layout))))), facts.audioTracks)
    }
    expected.optJSONArray("audioLayouts")?.let { layouts ->
      assertEquals(layouts.length(), facts.audioTracks.valueOrNull!!.size)
      for (i in 0 until layouts.length()) {
        val layout = AudioChannelLayout.valueOf(layouts.getString(i))
        assertEquals(MediaFact.Known(AudioFormat.Aac(layout)), facts.audioTracks.valueOrNull!![i].format)
      }
      assertEquals(listOf(0, 1, 2).map { MediaFact.Known(it) }, inspection.tracks.map { it.containerIndex })
    }
  }

  private fun assertDeferredFacts(facts: MediaFacts) {
    assertEquals(MediaFact.Unknown, facts.gopStructure)
    assertEquals(MediaFact.Unknown, facts.cadence)
    assertEquals(MediaFact.Unknown, facts.timestamps)
    assertEquals(MediaFact.Unknown, facts.frameRate)
    assertEquals(MediaFact.Unknown, facts.averageBitrate)
    assertEquals(MediaFact.Unknown, facts.maximumGopBitrate)
    assertEquals(MediaFact.Unknown, facts.maximumGopByteSize)
    assertEquals(MediaFact.Unknown, facts.maximumKeyframeIntervalSeconds)
    assertEquals(MediaFact.Unknown, facts.durationSeconds)
    assertEquals(MediaFact.Unknown, facts.audioVideoStartOffsetSeconds)
    assertEquals(MediaFact.Unknown, facts.editList)
  }

  @Test fun rejectMissingEmptyAndMalformedContainers() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File(context.cacheDir, "metadata-invalid.mp4")
    try {
      file.delete()
      assertTrue(MediaMetadataInspector().inspect(file) is MetadataInspectionResult.Failure)
      file.writeBytes(byteArrayOf())
      assertTrue(MediaMetadataInspector().inspect(file) is MetadataInspectionResult.Failure)
      file.writeBytes(ByteArray(64) { 42 })
      assertTrue(MediaMetadataInspector().inspect(file) is MetadataInspectionResult.Failure)
    } finally { file.delete() }
  }
}
