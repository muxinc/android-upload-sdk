package com.mux.video.upload.internal

import com.mux.exoplayeradapter.AbsRobolectricTest
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.HdrHandling
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Configuration retention through the public lifecycle, using unchanged original bytes.
 * Media conversion and generated-payload resume safety are exercised by their own integration
 * tests when the new pipeline is implemented.
 */
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class StandardInputLifecycleTests : AbsRobolectricTest() {
  private val dispatcher = StandardTestDispatcher()
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private lateinit var file: File
  private val workerOptions = mutableListOf<InputStandardization>()
  private val chunkOffsets = mutableListOf<Long>()
  private var preparations = 0

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    val context = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("mux_upload", 0).edit().clear().commit()
    MuxUploadSdk.initialize(context, resumeStoppedUploads = false)
    MuxUploadSdk.useLogger(MuxUploadSdk.noLogger())
    file = File.createTempFile("options", ".bin", context.cacheDir).apply { writeBytes(ByteArray(16)) }

    val realFactory = UploadJobFactory.create()
    val factory = mockk<UploadJobFactory>()
    every { factory.createUploadJob(any(), any()) } answers {
      realFactory.createUploadJob(firstArg(), scope)
    }
    mockkObject(MuxUploadSdk)
    every { MuxUploadSdk.uploadJobFactory() } returns factory
    mockkObject(TranscoderContext.Companion)
    every { TranscoderContext.create(any(), any(), any()) } answers {
      val upload = firstArg<UploadInfo>()
      // Always select the original file. Repeating this stub never regenerates payload bytes.
      mockk<TranscoderContext> {
        every { fileTranscoded } returns false
        coEvery { process() } coAnswers { preparations++; upload }
      }
    }
    mockkObject(UploadMetrics.Companion)
    every { UploadMetrics.create() } returns mockk(relaxed = true)
    mockkObject(ChunkWorker.Companion)
    every { ChunkWorker.create(any(), any(), any(), any()) } answers {
      val upload = secondArg<UploadInfo>()
      val chunk = firstArg<ChunkWorker.Chunk>()
      mockk<ChunkWorker> {
        coEvery { upload() } coAnswers {
          workerOptions += upload.inputStandardization
          chunkOffsets += chunk.startByte
          writeUploadState(upload, MuxUpload.Progress(bytesUploaded = 3, totalBytes = 16))
          awaitCancellation()
        }
      }
    }
  }

  @After
  fun tearDown() {
    MuxUploadManager.allUploadJobs().forEach { it.cancel() }
    dispatcher.scheduler.runCurrent()
    scope.cancel()
    dispatcher.scheduler.runCurrent()
    unmockkAll()
    Dispatchers.resetMain()
    file.delete()
  }

  @Test
  fun disabledToneMap1440OptionsRetainedWithOriginalPayload() {
    lifecycle(InputStandardization(false, MaximumResolution.Preset2560x1440, HdrHandling.ToneMapToSDR))
    assertEquals(0, preparations)
  }

  @Test
  fun enabledPreserve4kOptionsRetainedWithOriginalPayload() {
    lifecycle(InputStandardization(true, MaximumResolution.Preset3840x2160, HdrHandling.Preserve))
    assertTrue(preparations > 0)
  }

  private fun lifecycle(options: InputStandardization) {
    val upload = MuxUpload.Builder("https://example.invalid/upload", file)
      .standardizationRequested(options.standardizationRequested, options.maximumResolution)
      .hdrHandling(options.hdrHandling).build()
    upload.start()
    runUntil { workerOptions.size == 1 }
    assertOptions(upload, options)
    pauseAndDrain(upload)
    assertOptions(upload, options)
    upload.start()
    runUntil { workerOptions.size == 2 }
    assertOptions(upload, options)
    pauseAndDrain(upload)
    // Model loss of the in-process manager; resume through the public persistence path.
    MuxUploadManager.jobFinished(info(upload), forgetJob = false)
    val restored = MuxUploadManager.resumeAllCachedJobs().single()
    runUntil { workerOptions.size == 3 }
    assertOptions(MuxUploadManager.findUploadByFile(file)!!, options)
    restored.start(forceRestart = true)
    runUntil { workerOptions.size == 4 }
    assertOptions(restored, options)
    // These offsets apply only to the same original bytes, not regenerated media.
    assertEquals(listOf(0L, 3L, 3L, 0L), chunkOffsets)
    assertEquals(List(4) { options }, workerOptions)
  }

  private fun pauseAndDrain(upload: MuxUpload) {
    val job = info(upload).uploadJob!!
    upload.pause()
    runUntil { job.isCompleted }
  }

  // The factory performs file I/O on Dispatchers.IO. Pump the test scheduler until that work
  // reaches the suspended fake worker instead of assuming one runCurrent completes it.
  private fun runUntil(condition: () -> Boolean) = runBlocking {
    withTimeout(5000) {
      while (!condition()) {
        dispatcher.scheduler.runCurrent()
        delay(1)
      }
      dispatcher.scheduler.runCurrent()
    }
  }

  private fun assertOptions(upload: MuxUpload, options: InputStandardization) {
    assertEquals(options, info(upload).inputStandardization)
    assertEquals(options, readAllCachedUploads().single().inputStandardization)
    assertEquals(16L, upload.currentProgress.totalBytes)
  }

  private fun info(upload: MuxUpload): UploadInfo = MuxUpload::class.java
    .getDeclaredField("uploadInfo").apply { isAccessible = true }.get(upload) as UploadInfo
}
