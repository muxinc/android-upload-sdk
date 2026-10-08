package com.mux.video.upload.internal

import android.net.Uri
import com.mux.exoplayeradapter.AbsRobolectricTest
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import com.mux.video.upload.api.UploadStatus
import com.mux.video.upload.internal.standardization.PreparedUpload
import com.mux.video.upload.internal.standardization.SdrGeneratedFile
import com.mux.video.upload.internal.standardization.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class UploadPreparationLifecycleTests : AbsRobolectricTest() {
  private val dispatcher = StandardTestDispatcher()
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val context get() = RuntimeEnvironment.getApplication()
  private lateinit var source: File
  private lateinit var sibling: File
  private var preparations = 0
  private var prepare: suspend (UploadInfo) -> PreparedUpload = { PreparedUpload.Original() }
  private var work: suspend (ChunkWorker.Chunk, UploadInfo, MutableSharedFlow<MuxUpload.Progress>) -> MuxUpload.Progress =
    { chunk, _, _ -> MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
  private val chunks = mutableListOf<ChunkWorker.Chunk>()
  private val statuses = mutableListOf<UploadStatus>()
  private val results = mutableListOf<Result<MuxUpload.Progress>>()
  private val progress = mutableListOf<MuxUpload.Progress>()
  private val owned = mutableListOf<SdrGeneratedFile>()

  @Before fun setUp() {
    Dispatchers.setMain(dispatcher)
    context.getSharedPreferences("mux_upload", 0).edit().clear().commit()
    MuxUploadSdk.initialize(context, false)
    MuxUploadSdk.useLogger(MuxUploadSdk.noLogger())
    source = File.createTempFile("customer", ".mp4", context.cacheDir).apply { writeBytes(ByteArray(16) { it.toByte() }) }
    sibling = File(context.cacheDir, "mux-upload/customer-copy.mp4").apply { parentFile!!.mkdirs(); writeText("customer") }
    val real = UploadJobFactory(prepare = { preparations++; prepare(it) }, createWorker = { chunk, info, flow ->
      // The factory reuses its buffer; snapshot the actual payload for assertions.
      chunks += chunk.copy(sliceData = chunk.sliceData.copyOf(chunk.contentLength))
      mockk<ChunkWorker> { coEvery { upload() } coAnswers { work(chunk, info, flow) } }
    })
    val factory = mockk<UploadJobFactory>()
    every { factory.createUploadJob(any(), any()) } answers { real.createUploadJob(firstArg(), scope) }
    mockkObject(MuxUploadSdk)
    every { MuxUploadSdk.uploadJobFactory() } returns factory
    mockkObject(UploadMetrics.Companion)
    every { UploadMetrics.create() } returns mockk(relaxed = true)
  }

  @After fun tearDown() {
    MuxUploadManager.allUploadJobs().forEach { it.cancel() }
    scope.cancel()
    dispatcher.scheduler.runCurrent()
    owned.forEach { it.delete() }
    source.delete(); sibling.delete()
    unmockkAll()
    Dispatchers.resetMain()
  }

  @Test fun originalBytesCompleteWithCumulativeProgressAndOneResult() {
    val upload = observe(MuxUpload.create(info()))
    upload.start()
    pump { upload.isSuccessful }
    assertArrayEquals(source.readBytes(), chunks.flatMap { it.sliceData.toList() }.toByteArray())
    assertEquals(listOf(0L, 4L, 8L, 12L), chunks.map { it.startByte })
    assertEquals(16L, upload.currentProgress.bytesUploaded)
    assertEquals(16L, upload.currentProgress.totalBytes)
    assertEquals(1, results.size)
    assertTrue(readAllCachedUploads().isEmpty())
    assertTrue(source.exists()); assertTrue(sibling.exists())
    var lateResults = 0
    upload.setResultListener { lateResults++ }
    dispatcher.scheduler.runCurrent()
    assertEquals(1, lateResults)
  }
  @Test fun multiAudioFallbackUploadsOriginalWithOneSuccessfulResultEvenAfterResumeGateIsVerified() {
    val facts = compliantFacts().copy(averageBitrate = known(9_000_000L), audioTracks = known(listOf(
      AudioTrack(known(AudioFormat.Aac(AudioChannelLayout.Stereo))),
      AudioTrack(known(AudioFormat.Aac(AudioChannelLayout.Mono))))))
    val metadata = MediaMetadataInspection(known(ContainerKind.IsoBaseMedia), emptyList(), MediaFact.Unknown, facts, 0)
    val preparation = UploadPreparation(inspectMetadata = { MetadataInspectionResult.Success(metadata) },
      inspectSamples = { _, _, _ -> MediaSampleInspection(facts, SampleScanStatus.Complete) },
      convert = { _, _, _, _, _ -> error("Multi-audio export must never select a replacement track") })
    prepare = { preparation.prepare(it, context, generatedResumeVerified = true) }
    val upload = observe(MuxUpload.create(info()))
    upload.start()
    pump { upload.isSuccessful }
    assertArrayEquals(source.readBytes(), chunks.flatMap { it.sliceData.toList() }.toByteArray())
    assertEquals(16L, upload.currentProgress.totalBytes)
    assertEquals(1, results.size)
    assertTrue(results.single().isSuccess)
    assertTrue(source.exists()); assertTrue(sibling.exists())
  }

  @Test fun automaticLegacyRestorationUsesOriginalOffsetWithoutPreparation() {
    writeUploadState(info(), MuxUpload.Progress(bytesUploaded = 8, totalBytes = 16))
    // Remove new fields to model a pre-feature persistence record.
    val prefs = context.getSharedPreferences("mux_upload", 0)
    val json = org.json.JSONArray(prefs.getString("uploads", null))
    json.getJSONObject(0).getJSONObject("data").remove("generated_resume_blocked")
    json.getJSONObject(0).getJSONObject("data").remove("input_standardization")
    json.getJSONObject(0).getJSONObject("data").put("state", 0)
    prefs.edit().putString("uploads", json.toString()).commit()
    work = { _, _, _ -> awaitCancellation() }
    MuxUploadSdk.initialize(context, true)
    pump { chunks.isNotEmpty() }
    val restored = MuxUploadManager.resumeAllCachedJobs().single()
    assertTrue(restored.isRunning)
    assertTrue(restored.uploadStatus is UploadStatus.Uploading)
    assertEquals(0, preparations)
    assertEquals(8L, chunks.single().startByte)
    assertArrayEquals(source.readBytes().sliceArray(8..11), chunks.single().sliceData)
  }

  @Test fun preparationPauseStopsWorkIsSilentAndResumesOnlyOnStart() {
    var stopped = false
    prepare = { try { awaitCancellation() } finally { stopped = true } }
    val upload = observe(MuxUpload.create(info()))
    upload.start()
    pump { upload.uploadStatus is UploadStatus.Preparing }
    upload.pause()
    pump { stopped }
    assertTrue(upload.isPaused); assertFalse(upload.isRunning)
    assertEquals(0L, upload.currentProgress.bytesUploaded)
    assertTrue(statuses.last() is UploadStatus.UploadPaused)
    assertTrue(results.isEmpty()); assertTrue(chunks.isEmpty())
    assertEquals(0L, readLastByteForFile(info()))
    dispatcher.scheduler.runCurrent()
    assertEquals(1, preparations)
    prepare = { PreparedUpload.Original() }
    upload.start()
    pump { upload.isSuccessful }
    assertEquals(2, preparations)
  }

  @Test fun initializationDoesNotAutomaticallyResumeAnExplicitlyPausedRecord() {
    work = { _, _, _ -> awaitCancellation() }
    val upload = MuxUpload.create(info())
    upload.start()
    pump { chunks.size == 1 }
    upload.pause()
    dispatcher.scheduler.runCurrent()
    MuxUploadManager.jobFinished(internalInfo(upload), false)
    MuxUploadSdk.initialize(context, true)
    dispatcher.scheduler.runCurrent()
    assertEquals(1, chunks.size)
    assertEquals(1, readAllCachedUploads().size)
    assertTrue(MuxUploadManager.allUploadJobs().isEmpty())
    val restored = MuxUpload.create(info())
    restored.start()
    pump { chunks.size == 2 }
    assertEquals(1, preparations)
  }

  @Test fun uploadPauseUsesAcknowledgedProgressAndSuppressesLateEvents() {
    var late: MutableSharedFlow<MuxUpload.Progress>? = null
    work = { chunk, _, flow ->
      if (chunk.startByte == 0L) MuxUpload.Progress(bytesUploaded = 4)
      else { late = flow; flow.emit(MuxUpload.Progress(bytesUploaded = 3)); awaitCancellation() }
    }
    val upload = observe(MuxUpload.create(info()))
    upload.start()
    pump { upload.currentProgress.bytesUploaded == 7L }
    val managerHandle = MuxUploadManager.findUploadByFile(source)!!
    managerHandle.pause()
    pump { upload.isPaused }
    assertEquals(4L, upload.currentProgress.bytesUploaded)
    assertEquals(4L, readLastByteForFile(info()))
    val count = statuses.size
    late!!.tryEmit(MuxUpload.Progress(bytesUploaded = 4))
    dispatcher.scheduler.runCurrent()
    assertEquals(count, statuses.size)
    assertTrue(results.isEmpty())
    upload.start()
    pump { chunks.size == 3 }
    assertEquals(4L, chunks.last().startByte)
    assertEquals(1, preparations)
  }

  @Test fun cancellationDeletesOnlyOwnedFilesAndIgnoresLatePreparationCompletion() {
    val release = CompletableDeferred<Unit>()
    val generated = generated(8)
    prepare = { withContext(NonCancellable) { release.await(); PreparedUpload.Generated(generated) } }
    val upload = observe(MuxUpload.create(info()))
    upload.start()
    pump { preparations == 1 }
    val job = internalInfo(upload).uploadJob!!
    upload.cancel(); upload.cancel()
    release.complete(Unit)
    pump { job.isCompleted }
    val count = statuses.size
    upload.setStatusListener { statuses += it }
    upload.setProgressListener { progress += it }
    upload.setResultListener { results += it }
    dispatcher.scheduler.runCurrent()
    assertEquals(count, statuses.size)
    assertTrue(results.isEmpty()); assertTrue(chunks.isEmpty())
    assertFalse(generated.file.exists())
    assertTrue(source.exists()); assertTrue(sibling.exists())
    assertTrue(readAllCachedUploads().isEmpty())
    assertNull(MuxUploadManager.findUploadByFile(source))
  }

  @Test fun verifiedPreparationOutputSurvivesPauseAndUsesSelectedLengthOnResume() {
    val release = CompletableDeferred<Unit>()
    val generated = generated(8)
    prepare = { withContext(NonCancellable) { release.await(); PreparedUpload.Generated(generated) } }
    val upload = observe(MuxUpload.create(info()))
    upload.start()
    pump { preparations == 1 }
    val job = internalInfo(upload).uploadJob!!
    upload.pause()
    release.complete(Unit)
    pump { job.isCompleted }
    assertTrue(generated.file.exists())
    assertTrue(chunks.isEmpty()); assertTrue(results.isEmpty())
    upload.start()
    pump { upload.isSuccessful }
    assertEquals(1, preparations)
    assertEquals(8L, upload.currentProgress.totalBytes)
    assertEquals(8L, upload.currentProgress.bytesUploaded)
    assertEquals(listOf(8L, 8L), chunks.map { it.totalFileSize })
    assertFalse(generated.file.exists())
    assertTrue(source.exists()); assertTrue(sibling.exists())
  }

  @Test fun generatedRequestCannotResumeInProcessOrAfterRestoration() {
    val generated = generated(8)
    prepare = { PreparedUpload.Generated(generated) }
    work = { _, _, _ -> awaitCancellation() }
    val upload = observe(MuxUpload.create(info()))
    upload.start()
    pump { chunks.isNotEmpty() }
    upload.pause()
    dispatcher.scheduler.runCurrent()
    assertTrue(generated.file.exists())
    upload.start()
    pump { upload.uploadStatus is UploadStatus.UploadFailed }
    assertTrue(upload.error is GeneratedResumeBlockedException)
    assertEquals(1, chunks.size)
    val failedJob = internalInfo(upload).uploadJob
    upload.start(forceRestart = true)
    pump { internalInfo(upload).uploadJob !== failedJob && upload.uploadStatus is UploadStatus.UploadFailed }
    assertTrue(upload.error is GeneratedResumeBlockedException)
    assertEquals(1, chunks.size)
    assertTrue(readAllCachedUploads().single().generatedResumeBlocked)
    val restored = observe(MuxUploadManager.resumeAllCachedJobs().single())
    pump { restored.uploadStatus is UploadStatus.UploadFailed }
    assertTrue(restored.error is GeneratedResumeBlockedException)
    assertEquals(1, chunks.size)
  }

  @Test fun changedVerifiedOutputBeforeNetworkFallsBackToOriginalEvenWithSameSizeAndMtime() {
    val release = CompletableDeferred<Unit>()
    val generated = generated(8)
    prepare = { withContext(NonCancellable) { release.await(); PreparedUpload.Generated(generated) } }
    val upload = MuxUpload.create(info())
    upload.start()
    pump { preparations == 1 }
    val job = internalInfo(upload).uploadJob!!
    upload.pause(); release.complete(Unit)
    pump { job.isCompleted }
    val modified = generated.file.lastModified()
    generated.file.writeBytes(ByteArray(8) { 99 })
    assertTrue(generated.file.setLastModified(modified))
    upload.start()
    pump { upload.isSuccessful }
    assertEquals(16L, upload.currentProgress.totalBytes)
    assertArrayEquals(source.readBytes(), chunks.flatMap { it.sliceData.toList() }.toByteArray())
    assertFalse(generated.file.exists())
  }

  @Test fun latePreparationCannotReplacePayloadAfterResumeStartsANewAttempt() {
    val release = CompletableDeferred<Unit>()
    val generated = generated(8)
    prepare = { withContext(NonCancellable) { release.await(); PreparedUpload.Generated(generated) } }
    val upload = MuxUpload.create(info())
    upload.start()
    pump { preparations == 1 }
    upload.pause()
    prepare = { PreparedUpload.Original() }
    upload.start()
    release.complete(Unit)
    pump { upload.isSuccessful }
    assertEquals(2, preparations)
    assertEquals(16L, upload.currentProgress.totalBytes)
    assertFalse(generated.file.exists())
  }

  @Test fun cancellationFromStatusCallbackSuppressesProgressAndResult() {
    val upload = MuxUpload.create(info())
    upload.setResultListener { results += it }
    upload.setProgressListener { progress += it }
    upload.setStatusListener {
      statuses += it
      if (it is UploadStatus.Uploading) upload.cancel()
    }
    upload.start()
    pump { statuses.any { it is UploadStatus.Uploading } }
    dispatcher.scheduler.runCurrent()
    assertTrue(progress.isEmpty()); assertTrue(results.isEmpty())
    assertTrue(readAllCachedUploads().isEmpty())
  }

  @Test fun cancellingAFailedUploadRemovesItsRetainedResumeRecord() {
    work = { chunk, _, _ ->
      if (chunk.startByte == 0L) MuxUpload.Progress(bytesUploaded = 4)
      else throw java.io.IOException("Test transport failure")
    }
    val upload = MuxUpload.create(info())
    upload.start()
    pump { upload.uploadStatus is UploadStatus.UploadFailed && MuxUploadManager.findUploadByFile(source) == null }
    assertEquals(4L, readLastByteForFile(info()))
    upload.cancel()
    assertTrue(readAllCachedUploads().isEmpty())
    assertTrue(source.exists()); assertTrue(sibling.exists())
  }

  @Test fun staleCompletionCannotRemoveReplacementManagerJob() {
    work = { _, _, _ -> awaitCancellation() }
    val upload = MuxUpload.create(info())
    upload.start()
    pump { chunks.isNotEmpty() }
    val old = internalInfo(upload)
    upload.start(forceRestart = true)
    pump { chunks.size == 2 }
    MuxUploadManager.jobFinished(old)
    assertTrue(MuxUploadManager.findUploadByFile(source)!!.isRunning)
  }

  @Test fun unmanagedBuilderPauseAndCancelHaveSameListenerContract() {
    work = { _, _, _ -> awaitCancellation() }
    val upload = observe(MuxUpload.Builder("https://example.invalid/upload", source)
      .manageUploadTask(false).standardizationRequested(false).build())
    upload.start()
    pump { chunks.isNotEmpty() }
    assertNull(MuxUploadManager.findUploadByFile(source))
    upload.pause()
    dispatcher.scheduler.runCurrent()
    assertTrue(upload.isPaused); assertTrue(results.isEmpty())
    upload.cancel()
    assertTrue(readAllCachedUploads().isEmpty())
  }

  @Test fun unmanagedForceRestartStopsPreviousWorkerBeforeStartingAnother() {
    work = { _, _, _ -> awaitCancellation() }
    val upload = MuxUpload.Builder("https://example.invalid/upload", source)
      .manageUploadTask(false).standardizationRequested(false).build()
    upload.start()
    pump { chunks.size == 1 }
    val old = internalInfo(upload).uploadJob!!
    upload.start(forceRestart = true)
    pump { chunks.size == 2 }
    assertTrue(old.isCancelled)
    assertEquals(0L, chunks.last().startByte)
    upload.cancel()
  }

  @Test fun newDirectUploadUsesOriginalFallbackWithoutOldOffsetOrGeneratedPayload() {
    val generated = generated(8)
    prepare = { PreparedUpload.Generated(generated) }
    work = { _, _, _ -> awaitCancellation() }
    val upload = MuxUpload.create(info())
    upload.start()
    pump { chunks.size == 1 }
    upload.pause()
    prepare = { PreparedUpload.Original() }
    val next = MuxUpload.create(info().copy(remoteUri = Uri.parse("https://example.invalid/new-upload")))
    next.start()
    pump { chunks.size == 2 }
    assertEquals(0L, chunks.last().startByte)
    assertEquals(16L, chunks.last().totalFileSize)
    assertArrayEquals(source.readBytes().take(4).toByteArray(), chunks.last().sliceData)
    assertFalse(generated.file.exists())
  }

  private fun observe(upload: MuxUpload) = upload.apply {
    setStatusListener { statuses += it }
    setResultListener { results += it }
    setProgressListener { progress += it }
  }
  private fun generated(size: Int) = SdrGeneratedFile.allocate(context.cacheDir, 1)!!.also {
    it.file.writeBytes(ByteArray(size) { 42 }); assertTrue(it.recordValidation()); owned += it
  }
  private fun info() = UploadInfo(remoteUri = Uri.parse("https://example.invalid/upload"), inputFile = source,
    chunkSize = 4, retriesPerChunk = 3, optOut = true, uploadJob = null, statusFlow = null)
  private fun internalInfo(upload: MuxUpload) = MuxUpload::class.java.getDeclaredField("uploadInfo")
    .apply { isAccessible = true }.get(upload) as UploadInfo
  private fun pump(condition: () -> Boolean) = runBlocking {
    withTimeout(5000) {
      while (!condition()) { dispatcher.scheduler.runCurrent(); delay(1) }
      dispatcher.scheduler.runCurrent()
    }
  }
}
