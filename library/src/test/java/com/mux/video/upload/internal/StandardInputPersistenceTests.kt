package com.mux.video.upload.internal

import android.content.Context
import android.net.Uri
import com.mux.exoplayeradapter.AbsRobolectricTest
import com.mux.video.upload.api.HdrHandling
import com.mux.video.upload.api.MuxUpload
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

class StandardInputPersistenceTests : AbsRobolectricTest() {
  private val context: Context get() = RuntimeEnvironment.getApplication()
  private val prefs get() = context.getSharedPreferences("mux_upload", 0)

  @Before
  fun setUp() {
    prefs.edit().clear().commit()
    initializeUploadPersistence(context)
  }

  @Test
  fun roundTripEveryOptionCombination() {
    for (requested in listOf(true, false)) {
      for (resolution in MaximumResolution.entries) {
        for (handling in HdrHandling.entries) {
          val original = upload(InputStandardization(requested, resolution, handling))
          writeUploadState(original, MuxUpload.Progress(bytesUploaded = 5, totalBytes = 10))
          initializeUploadPersistence(context)
          assertEquals(original, readAllCachedUploads().single())
          assertEquals(5L, readLastByteForFile(original))
        }
      }
    }
  }

  @Test
  fun legacyRecordsDefaultMissingOptionsWithoutChangingProgress() {
    for (offset in listOf(0L, 5L)) {
      val record = JSONObject("""{"file":"/legacy/video","data":{"url":"https://example.invalid/upload","chunk_size":8388608,"retries_per_chunk":3,"opt_out":false,"saved_at_local_ms":1,"state":1,"bytes_sent":$offset}}""")
      prefs.edit().putString("uploads", JSONArray().put(record).toString()).commit()
      val restored = readAllCachedUploads().single()
      assertEquals(InputStandardization(), restored.inputStandardization)
      assertEquals(offset, readLastByteForFile(restored))
      assertEquals(8388608, restored.chunkSize)
      assertEquals(3, restored.retriesPerChunk)
      assertFalse(restored.optOut)
    }
  }

  @Test
  fun missingHdrDefaultsWithoutResettingOtherSavedOptions() {
    val original = upload(InputStandardization(false, MaximumResolution.Preset2560x1440, HdrHandling.ToneMapToSDR))
    writeUploadState(original, MuxUpload.Progress(bytesUploaded = 5))
    val records = JSONArray(prefs.getString("uploads", null))
    records.getJSONObject(0).getJSONObject("data").getJSONObject("input_standardization").remove("hdr_handling")
    prefs.edit().putString("uploads", records.toString()).commit()
    assertEquals(InputStandardization(false, MaximumResolution.Preset2560x1440), readAllCachedUploads().single().inputStandardization)
  }

  @Test
  fun partialOrUnknownOptionFieldsUseDefaults() {
    val original = upload(InputStandardization(false, MaximumResolution.Preset2560x1440, HdrHandling.ToneMapToSDR))
    writeUploadState(original, MuxUpload.Progress())
    val records = JSONArray(prefs.getString("uploads", null))
    val options = records.getJSONObject(0).getJSONObject("data").getJSONObject("input_standardization")
    options.remove("requested")
    options.put("maximum_resolution", "FutureResolution")
    options.put("hdr_handling", "FutureHdr")
    prefs.edit().putString("uploads", records.toString()).commit()
    assertEquals(InputStandardization(), readAllCachedUploads().single().inputStandardization)
  }

  private fun upload(options: InputStandardization) = UploadInfo(
    inputStandardization = options,
    inputFile = File("video").absoluteFile,
    remoteUri = Uri.parse("https://example.invalid/upload"),
    chunkSize = 8388608,
    retriesPerChunk = 3,
    optOut = false,
    uploadJob = null,
    statusFlow = null,
  )
}
