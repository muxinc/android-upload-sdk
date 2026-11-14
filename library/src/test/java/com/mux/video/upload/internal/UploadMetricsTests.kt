package com.mux.video.upload.internal

import android.media.MediaMetadataRetriever
import android.net.Uri
import com.mux.exoplayeradapter.AbsRobolectricTest
import com.mux.video.upload.MuxUploadSdk
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@Config(sdk = [23, 28])
class UploadMetricsTests : AbsRobolectricTest() {
  private lateinit var sent: JSONObject
  private val input = File("customer.mp4")

  @Before fun setUp() {
    UploadMetrics.initialize(RuntimeEnvironment.getApplication())
    val client = OkHttpClient.Builder().addInterceptor { chain ->
      val buffer = Buffer()
      chain.request().body!!.writeTo(buffer)
      sent = JSONObject(buffer.readUtf8())
      Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK").body("".toResponseBody()).build()
    }.build()
    val logger = MuxUploadSdk.noLogger()
    mockkObject(MuxUploadSdk)
    every { MuxUploadSdk.httpClient() } returns client
    every { MuxUploadSdk.logger } returns logger
    mockkConstructor(MediaMetadataRetriever::class)
    every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<String>()) } just Runs
    every { anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) } returns "12000"
    every { anyConstructed<MediaMetadataRetriever>().release() } just Runs
  }

  @After fun tearDown() { unmockkAll() }

  @Test fun readsDurationAndReleasesRetrieverOnOlderAndroid() = runBlocking {
    report()
    assertEquals(12L, sent.getJSONObject("data").getLong("input_duration"))
    verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().setDataSource(input.absolutePath) }
    verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().release() }
  }

  @Test fun failedMetadataStillReleasesRetrieverAndReportsUpload() = runBlocking {
    every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<String>()) } throws IllegalArgumentException("unreadable")
    report()
    assertEquals("upload_succeeded", sent.getString("type"))
    verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().release() }
  }

  @Test fun knownDurationSkipsMetadataRetriever() = runBlocking {
    report(durationMs = 12000)
    assertEquals(12L, sent.getJSONObject("data").getLong("input_duration"))
    verify(exactly = 0) { anyConstructed<MediaMetadataRetriever>().setDataSource(any<String>()) }
    verify(exactly = 0) { anyConstructed<MediaMetadataRetriever>().release() }
  }

  private suspend fun report(durationMs: Long = -1) {
    val info = UploadInfo(remoteUri = Uri.parse("https://example.invalid/upload"), inputFile = input,
      chunkSize = 8 * 1024 * 1024, retriesPerChunk = 3, optOut = false, uploadJob = null, statusFlow = null)
    val result = UploadMetrics.create().reportUploadSucceeded(0, 1000, durationMs, "test-session", info)
    assertTrue(result.toString(), result.isSuccess)
  }
}
