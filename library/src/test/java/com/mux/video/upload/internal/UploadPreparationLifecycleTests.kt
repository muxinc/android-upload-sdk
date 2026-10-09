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
  private var query: suspend (UploadInfo, Long) -> Long = { _, _ -> 0L }
  private var queries = 0
  private var preparations = 0
  private var beforeCreateJob: () -> Unit = {}
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
    val real = UploadJobFactory(prepare = { preparations++; prepare(it) }, queryOffset = { info, total -> queries++; query(info, total) }, createWorker = { chunk, info, flow ->
      // The factory reuses its buffer; snapshot the actual payload for assertions.
      chunks += chunk.copy(sliceData = chunk.sliceData.copyOf(chunk.contentLength))
      mockk<ChunkWorker> { coEvery { upload() } coAnswers { work(chunk, info, flow) } }
    })
    val factory = mockk<UploadJobFactory>()
    every { factory.createUploadJob(any(), any(), any()) } answers { beforeCreateJob(); real.createUploadJob(firstArg(), scope, thirdArg()) }
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
    upload.setProgressListener(null)
    upload.start()
    pump { upload.isSuccessful }
    assertArrayEquals(source.readBytes(), chunks.flatMap { it.sliceData.toList() }.toByteArray())
    assertEquals(listOf(0L, 4L, 8L, 12L), chunks.map { it.startByte })
    assertEquals(16L, upload.currentProgress.bytesUploaded)
    assertEquals(16L, upload.currentProgress.totalBytes)
    assertEquals(1, results.size)
    assertTrue(statuses.any { it is UploadStatus.UploadSuccess })
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

  @Test fun automaticLegacyRestorationUsesZeroAndNonzeroOriginalOffsetsWithoutPreparation() {
    for (offset in listOf(0L, 8L)) {
      val firstChunk = chunks.size
      writeUploadState(info(), MuxUpload.Progress(bytesUploaded = offset, totalBytes = 16))
      val prefs = context.getSharedPreferences("mux_upload", 0)
      val entries = org.json.JSONArray(prefs.getString("uploads", null))
      val data = entries.getJSONObject(0).getJSONObject("data")
      for (field in listOf("generated_resume_blocked", "input_standardization", "original_selected")) data.remove(field)
      data.put("state", 0)
      prefs.edit().putString("uploads", entries.toString()).commit()
      MuxUploadSdk.initialize(context, true)
      val restored = MuxUploadManager.findUploadByFile(source)!!
      pump { restored.isSuccessful }
      assertEquals(0, preparations)
      assertEquals(offset, chunks[firstChunk].startByte)
      assertArrayEquals(source.readBytes().sliceArray(offset.toInt() until offset.toInt() + 4), chunks[firstChunk].sliceData)
    }
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
    val progressCount = progress.size
    upload.pause(); dispatcher.scheduler.runCurrent()
    assertEquals(count, statuses.size)
    assertEquals(progressCount, progress.size)
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

  @Test fun unsupportedGeneratedQueryBlocksInProcessRestorationAndForceRestart() {
    query = { _, _ -> if (queries == 1) 0L else throw GeneratedResumeBlockedException() }
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
    assertTrue(readAllCachedUploads().single().generatedResumeBlocked)
    val restored = observe(MuxUploadManager.resumeAllCachedJobs().single())
    pump { restored.uploadStatus is UploadStatus.UploadFailed }
    assertTrue(restored.error is GeneratedResumeBlockedException)
    val failedJob = internalInfo(restored).uploadJob
    restored.start(forceRestart = true)
    pump { internalInfo(restored).uploadJob !== failedJob && restored.uploadStatus is UploadStatus.UploadFailed }
    assertTrue(restored.error is GeneratedResumeBlockedException)
    assertTrue(readUploadResumeState(info()).generatedResumeBlocked)
    val fresh = MuxUpload.create(info())
    fresh.start()
    pump { fresh.uploadStatus is UploadStatus.UploadFailed }
    assertTrue(fresh.error is GeneratedResumeBlockedException)
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
    pump { upload.uploadStatus is UploadStatus.UploadFailed && MuxUploadManager.findUploadByFile(source) != null }
    assertEquals(4L, readLastByteForFile(info()))
    upload.cancel()
    assertTrue(readAllCachedUploads().isEmpty())
    assertTrue(source.exists()); assertTrue(sibling.exists())
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
    val release = CompletableDeferred<Unit>()
    work = { chunk, _, _ -> release.await(); MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
    val upload = MuxUpload.Builder("https://example.invalid/upload", source)
      .manageUploadTask(false).standardizationRequested(false).build()
    upload.start()
    pump { chunks.size == 1 }
    val old = internalInfo(upload).uploadJob!!
    var result: Result<UploadStatus>? = null
    val waiter = scope.launch { result = upload.awaitSuccess() }
    dispatcher.scheduler.runCurrent()
    // Run the old waiter after teardown but before the new job is published.
    beforeCreateJob = { dispatcher.scheduler.runCurrent(); assertTrue(waiter.isActive); assertNull(result) }
    upload.start(forceRestart = true)
    pump { chunks.size == 2 }
    assertTrue(old.isCancelled)
    assertEquals(0L, chunks.last().startByte)
    assertTrue(waiter.isActive); assertNull(result)
    release.complete(Unit)
    pump { waiter.isCompleted && upload.isSuccessful }
    assertFalse(waiter.isCancelled)
    assertTrue(result!!.getOrThrow() is UploadStatus.UploadSuccess)
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

  @Test fun unmanagedSuccessClearsResumeStateAndCannotBeAutomaticallyRestored() {
    val upload = MuxUpload.Builder(info().remoteUri, source).manageUploadTask(false).build()
    upload.start(); pump { upload.isSuccessful }
    assertTrue(MuxUploadManager.allUploadJobs().isEmpty())
    assertTrue(readAllCachedUploads().isEmpty())
    val sent = chunks.size
    MuxUploadSdk.initialize(context, true)
    dispatcher.scheduler.runCurrent()
    assertTrue(MuxUploadManager.allUploadJobs().isEmpty())
    assertEquals(sent, chunks.size)
  }

  @Test fun freshHandleCannotUseBlockedGeneratedOffsetForOriginalBytes() {
    val state = UploadPreparationState().apply { generatedRequestStarted = true }
    val attempt = UploadAttempt(state, MuxUpload.Progress(bytesUploaded = 8, totalBytes = 16))
    val marked = info().update(attempt = attempt, statusFlow = attempt.status)
    writeUploadState(marked, attempt.confirmedProgress())
    assertEquals(0L, readLastByteForFile(info()))
    val upload = MuxUpload.create(info())
    upload.start(); pump { upload.uploadStatus is UploadStatus.UploadFailed }
    assertTrue(upload.error is GeneratedResumeBlockedException)
    assertTrue(chunks.isEmpty())
    assertEquals(0, preparations)
  }

  @Test fun cancelledHandleReturnsFailureWithoutRestartingOrCancellingCaller() {
    work = { _, _, _ -> awaitCancellation() }
    val upload = MuxUpload.create(info())
    upload.start(); pump { chunks.isNotEmpty() }
    val before = internalInfo(upload).uploadJob
    var waitingResult: Result<UploadStatus>? = null
    val waitingCaller = scope.launch { waitingResult = upload.awaitSuccess() }
    var cancelledCallerReturned = false
    val cancelledCaller = scope.launch { upload.awaitSuccess(); cancelledCallerReturned = true }
    dispatcher.scheduler.runCurrent()
    assertTrue(waitingCaller.isActive); assertTrue(cancelledCaller.isActive)
    cancelledCaller.cancel()
    upload.cancel(); upload.start(forceRestart = true)
    pump { waitingCaller.isCompleted && cancelledCaller.isCompleted }
    assertTrue(cancelledCaller.isCancelled)
    assertFalse(cancelledCallerReturned)
    assertFalse(waitingCaller.isCancelled)
    assertTrue(waitingResult!!.exceptionOrNull() is UploadCancelledException)
    assertTrue(results.isEmpty())
    assertSame(before, internalInfo(upload).uploadJob)
    var result: Result<UploadStatus>? = null
    val caller = scope.launch { result = upload.awaitSuccess() }
    pump { caller.isCompleted }
    assertTrue(result!!.exceptionOrNull() is UploadCancelledException)
    assertFalse(caller.isCancelled)
    assertTrue(scope.isActive)
    assertEquals(1, chunks.size)
  }

  @Test fun zeroBytePreparationPauseRunsPreparationAfterReconstruction() {
    prepare = { awaitCancellation() }
    val upload = MuxUpload.create(info())
    upload.start(); pump { preparations == 1 }
    upload.pause(); dispatcher.scheduler.runCurrent()
    val saved = readAllCachedUploads().single()
    assertFalse(saved.restoredFromOriginal)
    MuxUploadManager.jobFinished(internalInfo(upload), false)
    prepare = { PreparedUpload.Original() }
    val restored = MuxUploadManager.resumeAllCachedJobs().single()
    pump { restored.isSuccessful }
    assertEquals(2, preparations)
  }

  @Test fun zeroAcknowledgedOriginalRequestRemainsOriginalAfterRestoration() {
    work = { _, _, _ -> awaitCancellation() }
    val upload = MuxUpload.create(info())
    upload.start(); pump { chunks.size == 1 }
    upload.pause(); dispatcher.scheduler.runCurrent()
    assertEquals(0L, readLastByteForFile(info()))
    assertTrue(readAllCachedUploads().single().restoredFromOriginal)
    MuxUploadManager.jobFinished(internalInfo(upload), false)
    val restored = MuxUploadManager.resumeAllCachedJobs().single()
    pump { chunks.size == 2 }
    assertEquals(1, preparations)
    assertTrue(restored.isRunning)
    assertEquals(0L, chunks.last().startByte)
  }

  @Test fun existingHandleFollowsResumedAttemptAndItsCompletion() {
    work = { _, _, _ -> awaitCancellation() }
    val old = observe(MuxUpload.create(info()))
    old.start(); pump { chunks.size == 1 }
    old.pause(); dispatcher.scheduler.runCurrent()
    val second = MuxUploadManager.findUploadByFile(source)!!
    second.start(); pump { chunks.size == 2 }
    assertTrue(old.isRunning); assertFalse(old.isPaused)
    work = { chunk, _, _ -> MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
    second.pause(); second.start()
    pump { old.isSuccessful }
    assertTrue(second.isSuccessful)
    assertEquals(1, results.size)
    assertEquals(16L, old.currentProgress.bytesUploaded)
  }

  @Test fun cancellingGeneratedRequestKeepsBlockAcrossNewDestinationAndReinitialization() {
    val generated = generated(16)
    prepare = { PreparedUpload.Generated(generated) }
    work = { _, _, _ -> awaitCancellation() }
    val old = MuxUpload.create(info())
    old.start(); pump { chunks.size == 1 }
    old.cancel(); dispatcher.scheduler.runCurrent()
    assertTrue(readAllCachedUploads().isEmpty())
    assertFalse(generated.file.exists())
    prepare = { PreparedUpload.Original() }
    val next = MuxUpload.create(info().copy(remoteUri = Uri.parse("https://example.invalid/new-upload")))
    next.start(); pump { chunks.size == 2 }
    next.cancel(); dispatcher.scheduler.runCurrent()
    MuxUploadSdk.initialize(context, false)
    val reused = MuxUpload.create(info())
    reused.start(); pump { reused.uploadStatus is UploadStatus.UploadFailed }
    assertTrue(reused.error is GeneratedResumeBlockedException)
    assertEquals(2, chunks.size)
  }

  @Test fun staleUnmanagedCompletionCannotEraseDifferentDestinationResumeState() {
    val staleAttempt = UploadAttempt(UploadPreparationState(), MuxUpload.Progress())
    val stale = info().update(attempt = staleAttempt, statusFlow = staleAttempt.status)
    val currentAttempt = UploadAttempt(UploadPreparationState(), MuxUpload.Progress())
    val current = info().copy(remoteUri = Uri.parse("https://example.invalid/new-upload"))
      .update(attempt = currentAttempt, statusFlow = currentAttempt.status)
    writeUploadState(current, MuxUpload.Progress(bytesUploaded = 4))
    MuxUploadManager.jobFinished(stale)
    assertEquals(4L, readLastByteForFile(current))
  }

  @Test fun staleCompletionPreservesReplacementManagerJobAndItsPersistence() {
    work = { _, upload, _ ->
      val acknowledged = MuxUpload.Progress(bytesUploaded = 4, totalBytes = 16)
      upload.attempt!!.acknowledge(acknowledged) { writeUploadState(upload, acknowledged) }
      awaitCancellation()
    }
    val upload = MuxUpload.create(info())
    upload.start(); pump { chunks.size == 1 }
    val stale = internalInfo(upload)
    upload.pause(); dispatcher.scheduler.runCurrent()
    work = { _, current, _ ->
      val acknowledged = MuxUpload.Progress(bytesUploaded = 8, totalBytes = 16)
      current.attempt!!.acknowledge(acknowledged) { writeUploadState(current, acknowledged) }
      awaitCancellation()
    }
    upload.start(); pump { chunks.size == 2 }
    MuxUploadManager.jobFinished(stale)
    assertTrue(MuxUploadManager.findUploadByFile(source)!!.isRunning)
    assertEquals(8L, readLastByteForFile(internalInfo(upload)))
  }

  @Test fun transportFailureReportsSafeCategoryAndRetainsPublicException() {
    val failure = java.io.IOException("https://secret.invalid/private")
    val logger = mockk<MuxUploadSdk.Logger>(relaxed = true)
    val metrics = mockk<UploadMetrics>(relaxed = true)
    MuxUploadSdk.useLogger(logger)
    every { UploadMetrics.create() } returns metrics
    work = { _, _, _ -> throw failure }
    val upload = MuxUpload.create(info().copy(optOut = false))
    upload.start(); pump { upload.uploadStatus is UploadStatus.UploadFailed }
    assertSame(failure, upload.error)
    verify { logger.e("MuxUpload", "Upload failed: Io", null) }
    coVerify { metrics.reportUploadFailed(any(), any(), any(), "Upload failed: Io", any(), any()) }
  }

  @Test fun generatedDestinationBlockAlsoRejectsADifferentSourceFile() {
    val state = UploadPreparationState().apply { generatedRequestStarted = true }
    val attempt = UploadAttempt(state, MuxUpload.Progress(totalBytes = 16))
    val marked = info().update(attempt = attempt, statusFlow = attempt.status)
    writeUploadState(marked, attempt.confirmedProgress())
    forgetUploadState(marked)
    val different = MuxUpload.create(info().copy(inputFile = sibling))
    different.start(); pump { different.uploadStatus is UploadStatus.UploadFailed }
    assertTrue(different.error is GeneratedResumeBlockedException)
    assertTrue(chunks.isEmpty())
  }

  @Test fun forceRestartWithoutManagerEntryCancelsItsPreviousJob() {
    val release = CompletableDeferred<Unit>()
    work = { chunk, _, _ -> release.await(); MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
    val unmanaged = MuxUpload.Builder(info().remoteUri, source).manageUploadTask(false).build()
    unmanaged.start(); pump { chunks.size == 1 }
    val previous = internalInfo(unmanaged).uploadJob!!
    var result: Result<UploadStatus>? = null
    val waiter = scope.launch { result = unmanaged.awaitSuccess() }
    dispatcher.scheduler.runCurrent()
    val managed = MuxUpload.create(internalInfo(unmanaged))
    beforeCreateJob = { dispatcher.scheduler.runCurrent(); assertTrue(waiter.isActive); assertNull(result) }
    managed.start(forceRestart = true)
    pump { chunks.size == 2 }
    assertTrue(previous.isCancelled)
    assertTrue(managed.isRunning)
    assertTrue(waiter.isActive); assertNull(result)
    release.complete(Unit)
    pump { waiter.isCompleted && managed.isSuccessful }
    assertFalse(waiter.isCancelled)
    assertTrue(result!!.getOrThrow() is UploadStatus.UploadSuccess)
  }

  @Test fun failedHandleFollowsManagerRetryAndReceivesItsNewResult() {
    work = { chunk, _, _ ->
      if (chunk.startByte == 0L) MuxUpload.Progress(bytesUploaded = 4)
      else throw java.io.IOException("Test failure")
    }
    val original = observe(MuxUpload.create(info()))
    original.start(); pump { results.size == 1 }
    assertTrue(original.error is java.io.IOException)
    assertTrue(MuxUploadManager.findUploadByFile(source)!!.error is java.io.IOException)
    work = { _, _, _ -> awaitCancellation() }
    val resumed = MuxUploadManager.resumeAllCachedJobs().single()
    pump { chunks.size == 3 }
    assertTrue(original.isRunning)
    assertNull(original.error)
    assertEquals(4L, chunks.last().startByte)
    resumed.pause()
    work = { chunk, _, _ -> MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
    resumed.start(); pump { original.isSuccessful && results.size == 2 }
    assertTrue(results.first().isFailure)
    assertTrue(results.last().isSuccess)
  }

  @Test fun listenerChangeInsideSuccessCallbackDeliversSuccessOnce() {
    val upload = MuxUpload.create(info())
    var deliveries = 0
    var completions = 0
    upload.setResultListener { completions++ }
    upload.setStatusListener { status ->
      if (status is UploadStatus.UploadSuccess) {
        deliveries++
        // Bound this regression if a restarted observer replays success again.
        if (deliveries < 3) upload.setProgressListener(null) else upload.clearListeners()
      }
    }
    upload.start(); pump { upload.isSuccessful && completions > 0 }
    assertEquals(1, deliveries)
    assertEquals(1, completions)
  }

  @Test fun listenerRegistrationOnlyDeliversCurrentStateToThatListenerOnce() {
    val upload = MuxUpload.create(info())
    var first = 0
    var replacement = 0
    upload.setStatusListener { first++ }
    dispatcher.scheduler.runCurrent()
    assertEquals(1, first)
    upload.setProgressListener { progress += it }
    upload.setResultListener { results += it }
    dispatcher.scheduler.runCurrent()
    assertEquals(1, first)
    upload.setStatusListener { replacement++ }
    dispatcher.scheduler.runCurrent()
    assertEquals(1, replacement)
    upload.clearListeners()
    upload.setStatusListener { replacement++ }
    dispatcher.scheduler.runCurrent()
    assertEquals(2, replacement)
  }

  @Test fun neverStartedCancelPreservesAnotherAttemptsPausedRecord() {
    work = { _, _, _ -> awaitCancellation() }
    val managed = MuxUpload.create(info())
    managed.start(); pump { chunks.size == 1 }
    managed.pause(); dispatcher.scheduler.runCurrent()
    val saved = readUploadResumeState(info())
    for (autoManage in listOf(false, true)) {
      MuxUpload.Builder(info().remoteUri, source).manageUploadTask(autoManage).build().cancel()
      assertEquals(saved, readUploadResumeState(info()))
      assertEquals(1, readAllCachedUploads().size)
      assertTrue(managed.isPaused)
    }
  }

  @Test fun neverStartedCancelPreservesDiskRecordButExplicitRestartClearsOffset() {
    writeUploadState(info(), MuxUpload.Progress(bytesUploaded = 8, totalBytes = 16))
    for (autoManage in listOf(false, true)) {
      MuxUpload.Builder(info().remoteUri, source).manageUploadTask(autoManage).build().cancel()
      assertEquals(8L, readUploadResumeState(info()).bytesSent)
    }
    work = { _, _, _ -> awaitCancellation() }
    val restarted = MuxUpload.create(info())
    restarted.start(forceRestart = true); pump { chunks.size == 1 }
    assertEquals(0L, chunks.single().startByte)
  }

  @Test fun newDestinationFinishesOldHandleAndExistingWaiterWithReplacementFailure() {
    work = { _, _, _ -> awaitCancellation() }
    val old = observe(MuxUpload.create(info()))
    old.start(); pump { chunks.size == 1 }
    var awaited: Result<UploadStatus>? = null
    val waiter = scope.launch { awaited = old.awaitSuccess() }
    dispatcher.scheduler.runCurrent()
    val replacement = MuxUpload.create(info().copy(remoteUri = Uri.parse("https://example.invalid/replacement")))
    replacement.start(); pump { chunks.size == 2 && waiter.isCompleted && results.size == 1 }
    assertFalse(waiter.isCancelled)
    assertTrue(awaited!!.exceptionOrNull() is UploadReplacedException)
    assertTrue(old.uploadStatus is UploadStatus.UploadFailed)
    assertTrue(old.error is UploadReplacedException)
    assertTrue(results.single().exceptionOrNull() is UploadReplacedException)
    assertFalse(old.isRunning)
    old.pause(); old.start(forceRestart = true)
    var late: Result<UploadStatus>? = null
    val lateWaiter = scope.launch { late = old.awaitSuccess() }
    pump { lateWaiter.isCompleted }
    assertFalse(lateWaiter.isCancelled)
    assertTrue(late!!.exceptionOrNull() is UploadReplacedException)
    old.cancel(); dispatcher.scheduler.runCurrent()
    assertFalse(internalInfo(replacement).uploadJob!!.isCancelled)
    assertTrue(MuxUploadManager.findUploadByFile(source)!!.isRunning)
    assertTrue(replacement.isRunning)
    assertEquals(2, chunks.size)
    assertEquals(1, results.size)
  }

  @Test fun replacementPreservesSuccessThatWasPublishedBeforeListenersObservedIt() {
    work = { _, _, _ -> awaitCancellation() }
    val old = observe(MuxUpload.create(info()))
    old.start(); pump { chunks.size == 1 }
    val completed = UploadStatus.UploadSuccess(old.currentProgress)
    assertTrue(internalInfo(old).attempt!!.finish(completed))
    // Replace before the callback observer or awaiting caller can consume success.
    val replacement = MuxUpload.create(info().copy(remoteUri = Uri.parse("https://example.invalid/replacement")))
    replacement.start(); pump { chunks.size == 2 && results.size == 1 }
    assertTrue(results.single().isSuccess)
    assertTrue(old.isSuccessful)
    var awaited: Result<UploadStatus>? = null
    val waiter = scope.launch { awaited = old.awaitSuccess() }
    pump { waiter.isCompleted }
    assertSame(completed, awaited!!.getOrThrow())
    assertTrue(replacement.isRunning)
  }

  @Test fun replacingPausedDestinationFinishesOldHandleOnce() {
    work = { _, _, _ -> awaitCancellation() }
    val old = observe(MuxUpload.create(info()))
    old.start(); pump { chunks.size == 1 }
    old.pause(); dispatcher.scheduler.runCurrent()
    val replacement = MuxUpload.create(info().copy(remoteUri = Uri.parse("https://example.invalid/replacement")))
    replacement.start(); pump { chunks.size == 2 && results.size == 1 }
    assertFalse(old.isPaused)
    assertTrue(old.error is UploadReplacedException)
    assertTrue(results.single().isFailure)
    assertTrue(replacement.isRunning)
  }

  @Test fun replacementWaitsForOldPreparationAndDeletesItsLateOwnedOutput() {
    val release = CompletableDeferred<Unit>()
    val generated = generated(8)
    prepare = { withContext(NonCancellable) { release.await(); PreparedUpload.Generated(generated) } }
    val old = observe(MuxUpload.create(info()))
    old.start(); pump { preparations == 1 }
    prepare = { PreparedUpload.Original() }
    val replacement = MuxUpload.create(info().copy(remoteUri = Uri.parse("https://example.invalid/replacement")))
    replacement.start(); pump { results.size == 1 }
    assertTrue(chunks.isEmpty())
    assertTrue(old.error is UploadReplacedException)
    release.complete(Unit)
    pump { replacement.isSuccessful }
    assertFalse(generated.file.exists())
    assertTrue(source.exists()); assertTrue(sibling.exists())
    assertArrayEquals(source.readBytes(), chunks.flatMap { it.sliceData.toList() }.toByteArray())
    assertEquals(1, results.size)
  }

  @Test fun callerCancellationStillThrowsInsteadOfReturningReplacementFailure() {
    work = { _, _, _ -> awaitCancellation() }
    val old = MuxUpload.create(info())
    old.start(); pump { chunks.size == 1 }
    var returned = false
    val waiter = scope.launch { old.awaitSuccess(); returned = true }
    dispatcher.scheduler.runCurrent()
    waiter.cancel()
    MuxUpload.create(info().copy(remoteUri = Uri.parse("https://example.invalid/replacement"))).start()
    pump { chunks.size == 2 && waiter.isCompleted }
    assertTrue(waiter.isCancelled)
    assertFalse(returned)
  }

  @Test fun payloadSelectionIsWrittenOncePerAttemptAndAcknowledgementsRemainPersisted() {
    val prefs = spyk(context.getSharedPreferences("mux_upload", 0))
    val persistenceContext = mockk<android.content.Context>()
    every { persistenceContext.applicationContext } returns persistenceContext
    every { persistenceContext.getSharedPreferences("mux_upload", 0) } returns prefs
    initializeUploadPersistence(persistenceContext)
    work = { chunk, upload, _ ->
      val saved = readUploadResumeState(upload)
      assertEquals(chunk.startByte, saved.bytesSent)
      assertEquals(upload.attempt!!.id, saved.attemptId)
      assertTrue(saved.originalSelected)
      MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong())
    }
    val upload = MuxUpload.create(info())
    upload.start(); pump { upload.isSuccessful }
    // Selection once, four acknowledged chunk checkpoints, and terminal record removal.
    verify(atMost = 6) { prefs.edit() }
    assertTrue(readAllCachedUploads().isEmpty())
  }

  @Test fun resumedAttemptClaimsSavedOwnershipBeforeItsFirstAcknowledgement() {
    work = { _, _, _ -> awaitCancellation() }
    val upload = MuxUpload.create(info())
    upload.start(); pump { chunks.size == 1 }
    val firstOwner = readUploadResumeState(info()).attemptId
    upload.pause(); dispatcher.scheduler.runCurrent()
    upload.start(); pump { chunks.size == 2 }
    val saved = readUploadResumeState(info())
    assertNotEquals(firstOwner, saved.attemptId)
    assertEquals(internalInfo(upload).attempt!!.id, saved.attemptId)
    assertEquals(0L, saved.bytesSent)
    assertTrue(saved.originalSelected)
  }

  @Test fun restorationUsesSnapshotsForPausedAndRunningUploadsWithoutExtraStateReads() {
    writeUploadState(info(), MuxUpload.Progress(bytesUploaded = 8, totalBytes = 16))
    val running = info().copy(inputFile = sibling)
    writeUploadState(running, MuxUpload.Progress(bytesUploaded = 0, totalBytes = sibling.length()))
    val prefs = context.getSharedPreferences("mux_upload", 0)
    val entries = org.json.JSONArray(prefs.getString("uploads", null))
    entries.getJSONObject(1).getJSONObject("data").put("state", 0)
    prefs.edit().putString("uploads", entries.toString()).commit()
    mockkStatic(::readUploadResumeState)
    every { readUploadResumeState(any()) } throws AssertionError("Restoration must use its cached snapshot")
    work = { _, _, _ -> awaitCancellation() }
    MuxUploadSdk.initialize(context, true)
    pump { chunks.size == 1 }
    val paused = MuxUploadManager.findUploadByFile(source)!!
    assertTrue(paused.isPaused)
    assertEquals(8L, paused.currentProgress.bytesUploaded)
    assertEquals(16L, paused.currentProgress.totalBytes)
    assertTrue(paused.currentProgress.startTime > 0)
    assertTrue(paused.currentProgress.updatedTime >= paused.currentProgress.startTime)
    assertTrue(MuxUploadManager.findUploadByFile(sibling)!!.isRunning)
    assertEquals(sibling.length(), chunks.single().totalFileSize)
    assertEquals(2, MuxUploadManager.allUploadJobs().size)
    verify(exactly = 0) { readUploadResumeState(any()) }
    unmockkStatic(::readUploadResumeState)
    paused.start(); pump { chunks.size == 2 }
    assertTrue(paused.isRunning)
    assertEquals(8L, chunks.last().startByte)
  }

  @Test fun freshBuilderListenersFollowExistingRunningPausedFailedAndActiveSessions() {
    for (existing in listOf("restored running", "restored paused", "failed", "active")) {
      val release = CompletableDeferred<Unit>()
      val firstChunk = chunks.size
      work = { chunk, _, _ -> release.await(); MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
      when (existing) {
        "restored running", "restored paused" -> {
          writeUploadState(info(), MuxUpload.Progress(bytesUploaded = 8, totalBytes = 16))
          if (existing == "restored running") {
            val prefs = context.getSharedPreferences("mux_upload", 0)
            val entries = org.json.JSONArray(prefs.getString("uploads", null))
            entries.getJSONObject(0).getJSONObject("data").put("state", 0)
            prefs.edit().putString("uploads", entries.toString()).commit()
          }
          MuxUploadSdk.initialize(context, true)
        }
        "failed" -> {
          work = { _, _, _ -> throw java.io.IOException("Test failure") }
          val failed = MuxUpload.create(info())
          failed.start(); pump { failed.error != null }
          work = { chunk, _, _ -> release.await(); MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
        }
        else -> MuxUpload.create(info()).start()
      }
      val current = MuxUploadManager.findUploadByFile(source)!!
      val completions = mutableListOf<Result<MuxUpload.Progress>>()
      val updates = mutableListOf<MuxUpload.Progress>()
      val states = mutableListOf<UploadStatus>()
      val fresh = MuxUpload.Builder(info().remoteUri, source).build().apply {
        setResultListener { completions += it }
        setProgressListener { updates += it }
        setStatusListener { states += it }
      }
      // Ensure the observer is already watching the Builder's original session.
      dispatcher.scheduler.runCurrent()
      fresh.start(); pump { chunks.size > firstChunk && fresh.isRunning }
      release.complete(Unit)
      pump { fresh.isSuccessful }
      assertEquals(existing, 1, completions.size)
      assertTrue(existing, completions.single().isSuccess)
      assertEquals(existing, source.length(), updates.last().bytesUploaded)
      assertTrue(existing, states.last() is UploadStatus.UploadSuccess)
      assertTrue(existing, current.isSuccessful)
      assertTrue(MuxUploadManager.allUploadJobs().isEmpty())
    }
  }

  @Test fun generatedPauseQueriesEveryResumeAndSeeksPartialAcknowledgements() {
    val output = generated(12)
    output.file.writeBytes(ByteArray(12) { (it + 30).toByte() }); assertTrue(output.recordValidation())
    prepare = { PreparedUpload.Generated(output) }
    work = { chunk, _, flow ->
      if (chunk.startByte == 0L) MuxUpload.Progress(bytesUploaded = 2)
      else { flow.emit(MuxUpload.Progress(bytesUploaded = 0)); awaitCancellation() }
    }
    val upload = observe(MuxUpload.create(info()))
    upload.start(); pump { chunks.size == 2 }
    assertEquals(listOf(0L, 2L), chunks.map { it.startByte })
    assertArrayEquals(byteArrayOf(32, 33, 34, 35), chunks.last().sliceData)
    upload.pause(); pump { internalInfo(upload).uploadJob!!.isCompleted }
    assertEquals(2L, upload.currentProgress.bytesUploaded)
    assertTrue(output.file.exists()); assertTrue(results.isEmpty())
    // Remote can be ahead of persisted/UI progress when pause races an acknowledgement.
    query = { _, total -> assertEquals(12L, total); 3L }
    work = { chunk, _, _ -> MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
    upload.start(); pump { upload.isSuccessful && internalInfo(upload).uploadJob!!.isCompleted }
    assertEquals(3L, chunks[2].startByte)
    assertArrayEquals(byteArrayOf(33, 34, 35, 36), chunks[2].sliceData)
    assertEquals(2, queries); assertEquals(1, preparations)
    assertEquals(1, results.size); assertTrue(readAllCachedUploads().isEmpty())
    assertFalse(output.file.exists()); assertTrue(sibling.exists())
  }

  @Test fun durableGeneratedRecordRestoresWithoutOrdinaryPreferencesAndIgnoresSentOffset() {
    val output = generated(12)
    val state = GeneratedResumeState(PayloadIdentity.capture(source)!!, PreparationPhase.Uploading,
      output.file.absolutePath, output.validatedIdentity, networkStarted = true)
    runBlocking(Dispatchers.IO) { UploadPersistence.writeGenerated(info(), state) }
    context.getSharedPreferences("mux_upload", 0).edit().remove("uploads").remove("generated_upload_blocks").commit()
    MuxUploadSdk.initialize(context, false)
    query = { _, _ -> 3L }
    val restored = observe(MuxUploadManager.resumeAllCachedJobs().single())
    pump { restored.isSuccessful && internalInfo(restored).uploadJob!!.isCompleted }
    assertEquals(0, preparations); assertEquals(1, queries)
    assertEquals(listOf(3L, 7L, 11L), chunks.map { it.startByte })
    assertEquals(12L, restored.currentProgress.totalBytes)
    assertEquals(1, results.size); assertFalse(output.file.exists())
    assertTrue(readAllCachedUploads().isEmpty())
  }

  @Test fun invalidGeneratedPayloadNeedsZeroRemoteBytesAndIdenticalSourceBeforeRepreparing() {
    for ((label, offset, changeSource, missing, expectedSuccess) in listOf(
      ResumeCase("missing-zero", 0, false, true, true),
      ResumeCase("changed-zero", 0, false, false, true),
      ResumeCase("missing-partial", 3, false, true, false),
      ResumeCase("changed-partial", 3, false, false, false),
      ResumeCase("source-changed-zero", 0, true, true, false))) {
      val target = info().copy(remoteUri = Uri.parse("https://example.invalid/$label"))
      val sourceBytes = source.readBytes()
      val sourceMtime = source.lastModified()
      val output = generated(12)
      val state = GeneratedResumeState(PayloadIdentity.capture(source)!!, PreparationPhase.Uploading,
        output.file.absolutePath, output.validatedIdentity, networkStarted = true)
      runBlocking(Dispatchers.IO) { UploadPersistence.writeGenerated(target, state) }
      if (missing) output.delete() else {
        val modified = output.file.lastModified()
        output.file.writeBytes(ByteArray(12) { 99 }); assertTrue(output.file.setLastModified(modified))
      }
      if (changeSource) {
        source.writeBytes(ByteArray(16) { 88 }); assertTrue(source.setLastModified(sourceMtime))
      }
      val before = chunks.size
      query = { _, _ -> offset }
      prepare = { PreparedUpload.Generated(generated(8)) }
      val upload = MuxUpload.create(target)
      upload.start(); pump { internalInfo(upload).uploadJob!!.isCompleted }
      if (expectedSuccess) {
        assertTrue(label, upload.isSuccessful)
        assertEquals(0L, chunks[before].startByte)
        assertEquals(8L, chunks[before].totalFileSize)
      } else {
        assertTrue(label, upload.error is GeneratedResumeBlockedException)
        assertEquals(before, chunks.size)
        assertTrue(readUploadResumeState(target).generatedResumeBlocked)
        upload.cancel()
      }
      source.writeBytes(sourceBytes); assertTrue(source.setLastModified(sourceMtime))
    }
    assertTrue(sibling.exists()); assertTrue(source.exists())
  }

  @Test fun abandonedTrackedPartialIsCleanedBeforeZeroBytePreparation() {
    val partial = SdrGeneratedFile.allocate(context.cacheDir, 1)!!
    owned += partial
    partial.file.writeText("unfinished export")
    val state = GeneratedResumeState(PayloadIdentity.capture(source)!!, ownedPath = partial.file.absolutePath)
    runBlocking(Dispatchers.IO) { UploadPersistence.writeGenerated(info(), state) }
    prepare = { assertFalse(partial.file.exists()); PreparedUpload.Original() }
    val upload = MuxUpload.create(info())
    upload.start(); pump { upload.isSuccessful && internalInfo(upload).uploadJob!!.isCompleted }
    assertEquals(0, queries); assertEquals(1, preparations)
    assertTrue(sibling.exists()); assertTrue(source.exists())
  }

  @Test fun durableCommitFailurePreventsEveryGeneratedRequestWithoutBlockingPause() {
    val prefs = spyk(context.getSharedPreferences("mux_upload", 0))
    val persistenceContext = mockk<android.content.Context>()
    every { persistenceContext.applicationContext } returns persistenceContext
    every { persistenceContext.getSharedPreferences("mux_upload", 0) } returns prefs
    var commits = 0
    val uiThread = Thread.currentThread()
    every { prefs.edit() } answers {
      val editor = mockk<android.content.SharedPreferences.Editor>()
      every { editor.putString(any(), any()) } returns editor
      every { editor.apply() } just Runs
      every { editor.commit() } answers {
        assertNotSame(uiThread, Thread.currentThread())
        commits++
        false
      }
      editor
    }
    initializeUploadPersistence(persistenceContext)
    prepare = { PreparedUpload.Generated(generated(12)) }
    val upload = MuxUpload.create(info())
    upload.start(); pump { upload.uploadStatus is UploadStatus.UploadFailed }
    assertTrue("Failed without commit: ${upload.error}", commits > 0); assertTrue(chunks.isEmpty()); assertEquals(0, queries)
    assertTrue(upload.error is GeneratedResumeBlockedException)
  }

  @Test fun releaseTimeoutFailsClosedUntilTheOldEngineActuallyReleases() {
    val released = CompletableDeferred<Unit>()
    work = { _, _, _ -> awaitCancellation() }
    val upload = MuxUpload.create(info())
    upload.start(); pump { chunks.size == 1 }
    upload.pause(); pump { internalInfo(upload).uploadJob!!.isCompleted }
    internalInfo(upload).attempt!!.preparation.releaseBarrier = { released.await() }
    upload.start(); dispatcher.scheduler.runCurrent()
    assertEquals(1, chunks.size)
    dispatcher.scheduler.advanceTimeBy(PREPARATION_RELEASE_TIMEOUT_MS + 1)
    pump { upload.uploadStatus is UploadStatus.UploadFailed }
    assertTrue(upload.error is PreparationReleasePendingException)
    assertEquals(1, chunks.size)
    released.complete(Unit)
    work = { chunk, _, _ -> MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
    upload.start(); pump { upload.isSuccessful }
    assertEquals(1, preparations) // The original payload choice still bypasses another export.
  }

  @Test fun cancelledPausedGeneratedRecordIsRetiredBeforeReinitialization() {
    val output = generated(12)
    prepare = { PreparedUpload.Generated(output) }
    work = { _, _, _ -> awaitCancellation() }
    val upload = MuxUpload.create(info())
    upload.start(); pump { chunks.size == 1 }
    upload.pause(); pump { internalInfo(upload).uploadJob!!.isCompleted }
    upload.cancel()
    pump { context.getSharedPreferences("mux_upload", 0).getString("generated_payloads_v1", null) == "[]" }
    MuxUploadSdk.initialize(context, true)
    assertTrue(readAllCachedUploads().isEmpty())
    assertTrue(readUploadResumeState(info()).generatedResumeBlocked)
    assertFalse(output.file.exists())
  }

  @Test fun staleDurableDestinationDoesNotEraseCurrentPausedSnapshot() {
    val output = generated(12)
    val state = GeneratedResumeState(PayloadIdentity.capture(source)!!, PreparationPhase.Uploading,
      output.file.absolutePath, output.validatedIdentity, networkStarted = true)
    val old = info().copy(remoteUri = Uri.parse("https://example.invalid/older"))
    runBlocking(Dispatchers.IO) { UploadPersistence.writeGenerated(old, state) }
    val owner = UploadAttempt(UploadPreparationState().apply { generatedState = state },
      MuxUpload.Progress(bytesUploaded = 3, totalBytes = 12))
    val current = info().update(attempt = owner, statusFlow = owner.status)
    runBlocking(Dispatchers.IO) { UploadPersistence.writeGenerated(current, state) }
    owner.pause { writeUploadState(current, it) }
    val snapshot = readCachedUploadSnapshots().single()
    assertEquals(current.remoteUri, snapshot.upload.remoteUri)
    assertTrue(snapshot.resumeState.paused)
    assertEquals(3L, snapshot.resumeState.bytesSent)
    assertEquals(state, snapshot.resumeState.generated)
    runBlocking(Dispatchers.IO) { UploadPersistence.retireGenerated(old); UploadPersistence.retireGenerated(current) }
  }

  @Test fun zeroRemoteRepreparationCanFallBackToOriginalAndThenPauseAndResume() {
    val output = generated(12)
    val state = GeneratedResumeState(PayloadIdentity.capture(source)!!, PreparationPhase.Uploading,
      output.file.absolutePath, output.validatedIdentity, networkStarted = true)
    val savedAttempt = UploadAttempt(UploadPreparationState().apply { generatedState = state },
      MuxUpload.Progress(bytesUploaded = 8, totalBytes = 12))
    writeUploadState(info().update(attempt = savedAttempt), savedAttempt.confirmedProgress())
    runBlocking(Dispatchers.IO) { UploadPersistence.writeGenerated(info(), state) }
    output.delete()
    prepare = { PreparedUpload.Original() }
    work = { _, _, _ -> awaitCancellation() }
    val upload = MuxUpload.create(info())
    upload.start(); pump { chunks.size == 1 }
    assertEquals(16L, chunks.single().totalFileSize)
    assertEquals(0L, chunks.single().startByte)
    upload.pause(); pump { internalInfo(upload).uploadJob!!.isCompleted }
    assertFalse(readUploadResumeState(info()).generatedResumeBlocked)
    assertNull(readUploadResumeState(info()).generated)
    work = { chunk, _, _ -> MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
    upload.start(); pump { upload.isSuccessful }
    assertEquals(1, queries); assertEquals(1, preparations)
  }

  @Test fun zeroRemoteRepreparationUsesDurableOptionsOnAFreshHandle() {
    val output = generated(12)
    val options = InputStandardization(true, MaximumResolution.Preset1280x720,
      com.mux.video.upload.api.HdrHandling.Preserve)
    val original = info().copy(inputStandardization = options)
    val state = GeneratedResumeState(PayloadIdentity.capture(source)!!, PreparationPhase.Uploading,
      output.file.absolutePath, output.validatedIdentity, networkStarted = true)
    runBlocking(Dispatchers.IO) { UploadPersistence.writeGenerated(original, state) }
    output.delete()
    prepare = { restored ->
      assertEquals(options, restored.inputStandardization)
      PreparedUpload.Generated(generated(8))
    }
    val fresh = MuxUpload.create(info().copy(inputStandardization = InputStandardization(false)))
    fresh.start(); pump { fresh.isSuccessful }
    assertEquals(1, preparations)
    assertEquals(8L, fresh.currentProgress.totalBytes)
  }

  @Test fun blockedCleanupDeletesOnlyTrackedFilesOwnedByAPreviousProcess() {
    for ((label, previousProcess, customerPath) in listOf(
      Triple("dead-owned", true, false), Triple("live-owned", false, false), Triple("customer", true, true))) {
      val output = generated(12)
      val target = info().copy(remoteUri = Uri.parse("https://example.invalid/$label"))
      val state = GeneratedResumeState(PayloadIdentity.capture(source)!!,
        ownedPath = if (customerPath) sibling.absolutePath else output.file.absolutePath)
      runBlocking(Dispatchers.IO) { UploadPersistence.writeGenerated(target, state) }
      val prefs = context.getSharedPreferences("mux_upload", 0)
      if (previousProcess) {
        val records = org.json.JSONArray(prefs.getString("generated_payloads_v1", null))
        val row = (0 until records.length()).map { records.getJSONObject(it) }
          .single { it.getJSONObject("data").getString("url") == target.remoteUri.toString() }
        row.getJSONObject("data").put("process_identity", "previous-process")
        prefs.edit().putString("generated_payloads_v1", records.toString()).commit()
      }
      val hash = java.security.MessageDigest.getInstance("SHA-256")
        .digest(target.remoteUri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
      val blocked = org.json.JSONArray(prefs.getString("generated_upload_blocks", null) ?: "[]").put(hash)
      prefs.edit().putString("generated_upload_blocks", blocked.toString()).commit()
      runBlocking { UploadPersistence.scheduleBlockedCleanup(context).join() }
      assertEquals(label, !previousProcess || customerPath, output.file.exists())
      assertTrue(sibling.exists()); assertTrue(source.exists())
      assertTrue(readUploadResumeState(target).generatedResumeBlocked)
    }
  }

  private data class ResumeCase(val label: String, val offset: Long, val changeSource: Boolean,
    val missing: Boolean, val expectedSuccess: Boolean)


  @Test fun offlineGeneratedRestorationRetainsPayloadAndCanResumeOnTheSameDestination() {
    val output = generated(12)
    val target = info().copy(retriesPerChunk = 2)
    val state = GeneratedResumeState(PayloadIdentity.capture(source)!!, PreparationPhase.Uploading,
      output.file.absolutePath, output.validatedIdentity, networkStarted = true)
    runBlocking(Dispatchers.IO) { UploadPersistence.writeGenerated(target, state) }
    val offline = java.io.IOException("Offline")
    query = { _, _ -> throw offline }
    val upload = observe(MuxUpload.create(target))
    upload.start(); pumpRetries { internalInfo(upload).uploadJob!!.isCompleted }
    assertSame(offline, upload.error)
    assertEquals(3, queries); assertTrue(chunks.isEmpty())
    assertTrue(output.file.exists())
    val retained = readUploadResumeState(target)
    assertFalse(retained.generatedResumeBlocked); assertEquals(state.payload, retained.generated!!.payload)
    query = { _, _ -> 3L }
    upload.start(); pump { upload.isSuccessful && internalInfo(upload).uploadJob!!.isCompleted }
    assertEquals(0, preparations); assertEquals(3L, chunks.first().startByte)
    assertEquals(1, results.count { it.isSuccess }); assertFalse(output.file.exists())
  }

  @Test fun uncertainGeneratedChunkQueriesBeforeResendingAndSeeksToPartialAcknowledgement() {
    val output = generated(12)
    output.file.writeBytes(ByteArray(12) { (it + 30).toByte() }); assertTrue(output.recordValidation())
    prepare = { PreparedUpload.Generated(output) }
    query = { _, _ -> if (queries == 1) 0L else 6L }
    work = { chunk, _, _ ->
      if (chunks.size == 2) throw java.io.IOException("Uncertain chunk")
      MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong())
    }
    val upload = observe(MuxUpload.create(info()))
    upload.start(); pumpRetries { upload.isSuccessful && internalInfo(upload).uploadJob!!.isCompleted }
    assertEquals(listOf(0L, 4L, 6L, 10L), chunks.map { it.startByte })
    assertArrayEquals(byteArrayOf(36, 37, 38, 39), chunks[2].sliceData)
    assertEquals(2, queries); assertEquals(1, preparations); assertEquals(1, results.size)
    assertEquals(12L, upload.currentProgress.bytesUploaded); assertFalse(output.file.exists())
  }

  @Test fun generatedChunkAndRecoveryQueriesShareOneRetryBudget() {
    val output = generated(12)
    prepare = { PreparedUpload.Generated(output) }
    query = { _, _ -> if (queries == 1) 0L else throw java.io.IOException("Offline query") }
    work = { _, _, _ -> throw java.io.IOException("Uncertain chunk") }
    val upload = MuxUpload.create(info().copy(retriesPerChunk = 2))
    upload.start(); pumpRetries { internalInfo(upload).uploadJob!!.isCompleted }
    assertTrue(upload.error is java.io.IOException)
    assertEquals(3, queries); assertEquals(1, chunks.size)
    assertTrue(output.file.exists()); assertFalse(readUploadResumeState(info()).generatedResumeBlocked)
    query = { _, _ -> 0L }
    work = { chunk, _, _ -> MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
    upload.start(); pump { upload.isSuccessful && internalInfo(upload).uploadJob!!.isCompleted }
    assertEquals(1, preparations); assertFalse(output.file.exists())
  }

  @Test fun generatedRecoveryQueryRejectsRegressingAndUnrelatedOffsets() {
    for ((label, offset) in listOf("regressed" to 3L, "ahead" to 9L)) {
      val target = info().copy(remoteUri = Uri.parse("https://example.invalid/$label"))
      prepare = { PreparedUpload.Generated(generated(12)) }
      val queryStart = queries
      val chunkStart = chunks.size
      query = { _, _ -> if (queries == queryStart + 1) 0L else offset }
      work = { chunk, _, _ ->
        if (chunks.size == chunkStart + 2) throw java.io.IOException("Uncertain chunk")
        MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong())
      }
      val upload = MuxUpload.create(target)
      upload.start(); pumpRetries { internalInfo(upload).uploadJob!!.isCompleted }
      assertTrue(label, upload.error is GeneratedResumeBlockedException)
      assertTrue(label, readUploadResumeState(target).generatedResumeBlocked)
      assertEquals(chunkStart + 2, chunks.size)
    }
  }

  @Test fun finalGeneratedChunkCanCompleteThroughRecoveryQueryWithoutBeingResent() {
    prepare = { PreparedUpload.Generated(generated(4)) }
    query = { _, _ -> if (queries == 1) 0L else 4L }
    work = { _, _, _ -> throw java.io.IOException("Lost final response") }
    val upload = observe(MuxUpload.create(info()))
    upload.start(); pumpRetries { upload.isSuccessful && internalInfo(upload).uploadJob!!.isCompleted }
    assertEquals(2, queries); assertEquals(1, chunks.size); assertEquals(1, results.size)
    assertEquals(4L, upload.currentProgress.bytesUploaded)
  }

  @Test fun pauseDuringGeneratedRetryBackoffRemainsSilentAndResumesWithAQuery() {
    val output = generated(12)
    prepare = { PreparedUpload.Generated(output) }
    work = { _, _, _ -> throw java.io.IOException("Temporary failure") }
    val upload = observe(MuxUpload.create(info()))
    upload.start(); pump { chunks.size == 1 }
    upload.pause(); pump { internalInfo(upload).uploadJob!!.isCompleted }
    dispatcher.scheduler.advanceTimeBy(10_000); dispatcher.scheduler.runCurrent()
    assertTrue(results.isEmpty()); assertEquals(1, queries); assertEquals(1, chunks.size)
    assertTrue(output.file.exists()); assertEquals(0L, upload.currentProgress.bytesUploaded)
    work = { chunk, _, _ -> MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
    upload.start(); pump { upload.isSuccessful && internalInfo(upload).uploadJob!!.isCompleted }
    assertEquals(2, queries); assertEquals(1, preparations); assertEquals(1, results.size)
  }

  @Test fun forceRestartBeforeGeneratedNetworkRepreparesForManagedAndUnmanagedHandles() {
    for (managed in listOf(true, false)) {
      val url = "https://example.invalid/restart-$managed"
      val target = info().copy(remoteUri = Uri.parse(url))
      val partial = SdrGeneratedFile.allocate(context.cacheDir, 1)!!
      owned += partial
      prepare = { running ->
        val state = running.attempt!!.preparation.generatedState!!.copy(ownedPath = partial.file.absolutePath)
        running.attempt.preparation.generatedState = state
        running.attempt.trackCleanup(partial)
        withContext(Dispatchers.IO) { UploadPersistence.writeGenerated(running, state) }
        awaitCancellation()
      }
      val before = preparations
      val upload = MuxUpload.Builder(url, source).manageUploadTask(managed).build()
      upload.start(); pump { readUploadResumeState(target).generated?.ownedPath != null }
      upload.pause(); pump { internalInfo(upload).uploadJob!!.isCompleted }
      assertFalse(readUploadResumeState(target).generated!!.networkStarted)
      prepare = { PreparedUpload.Original() }
      upload.start(forceRestart = true); pump { internalInfo(upload).uploadJob!!.isCompleted }
      assertFalse(readUploadResumeState(target).generatedResumeBlocked)
      assertTrue(upload.isSuccessful); assertEquals(before + 2, preparations)
      assertFalse(partial.file.exists())
    }
    assertEquals(0, queries)
  }

  @Test fun generatedQueryNetworkMarkerNeverRegressesBeforeTransport() {
    val records = mutableListOf<GeneratedResumeState>()
    mockkObject(UploadPersistence)
    every { UploadPersistence.writeGenerated(any(), any()) } answers {
      records += secondArg<GeneratedResumeState>()
      callOriginal()
    }
    prepare = { PreparedUpload.Generated(generated(12)) }
    val upload = MuxUpload.create(info())
    upload.start(); pump { upload.isSuccessful && internalInfo(upload).uploadJob!!.isCompleted }
    assertTrue(records.isNotEmpty())
    assertTrue("Marker flags: ${records.map { it.networkStarted }}", records.all { it.networkStarted })
  }


  @Test fun generatedDataRetriesAreBoundedAndZeroBudgetDoesNotIssueRecoveryQueries() {
    for (budget in listOf(0, 2)) {
      val target = info().copy(remoteUri = Uri.parse("https://example.invalid/budget-$budget"), retriesPerChunk = budget)
      val output = generated(12)
      prepare = { PreparedUpload.Generated(output) }
      query = { _, _ -> 0L }
      work = { _, _, _ -> throw java.io.IOException("Uncertain chunk") }
      val queryStart = queries
      val chunkStart = chunks.size
      val upload = MuxUpload.create(target)
      upload.start(); pumpRetries { internalInfo(upload).uploadJob!!.isCompleted }
      assertTrue(upload.error is java.io.IOException)
      assertEquals(budget + 1, queries - queryStart)
      assertEquals(budget + 1, chunks.size - chunkStart)
      assertTrue(output.file.exists()); assertFalse(readUploadResumeState(target).generatedResumeBlocked)
      upload.cancel(); pump { !output.file.exists() }
    }
  }

  @Test fun restoredPreparationQueriesItsNewPayloadBeforeSendingAnyBytes() {
    val partial = SdrGeneratedFile.allocate(context.cacheDir, 1)!!
    owned += partial
    val saved = GeneratedResumeState(PayloadIdentity.capture(source)!!, ownedPath = partial.file.absolutePath)
    runBlocking(Dispatchers.IO) { UploadPersistence.writeGenerated(info(), saved) }
    prepare = { PreparedUpload.Generated(generated(12)) }
    query = { _, total -> assertEquals(12L, total); 2L }
    val upload = MuxUpload.create(info())
    upload.start(); pump { internalInfo(upload).uploadJob!!.isCompleted }
    assertEquals(1, queries); assertTrue(chunks.isEmpty())
    assertTrue(upload.error is GeneratedResumeBlockedException)
    assertTrue(readUploadResumeState(info()).generatedResumeBlocked)
    assertFalse(partial.file.exists())
  }


  @Test fun slowReleaseRestartThenPauseResumesWithoutAnOldOwnersHiddenKey() {
    val partial = SdrGeneratedFile.allocate(context.cacheDir, 1)!!
    owned += partial
    prepare = { running ->
      val state = running.attempt!!.preparation.generatedState!!.copy(ownedPath = partial.file.absolutePath)
      running.attempt.preparation.generatedState = state
      running.attempt.trackCleanup(partial)
      withContext(Dispatchers.IO) { UploadPersistence.writeGenerated(running, state) }
      awaitCancellation()
    }
    val upload = observe(MuxUpload.create(info()))
    upload.start(); pump { readUploadResumeState(info()).generated?.ownedPath != null }
    upload.pause(); pump { internalInfo(upload).uploadJob!!.isCompleted }
    val released = CompletableDeferred<Unit>()
    internalInfo(upload).attempt!!.preparation.releaseBarrier = { released.await() }
    // Delay retirement so restart snapshots the untouched record before it becomes abandoned.
    val retirementEntered = java.util.concurrent.CountDownLatch(1)
    val allowRetirement = java.util.concurrent.CountDownLatch(1)
    mockkObject(UploadPersistence)
    every { UploadPersistence.writeGenerated(any(), match { it.abandoned }) } answers {
      retirementEntered.countDown()
      check(allowRetirement.await(3, java.util.concurrent.TimeUnit.SECONDS))
      callOriginal()
    }
    prepare = { PreparedUpload.Generated(generated(12)) }
    work = { _, _, _ -> awaitCancellation() }
    try {
      upload.start(forceRestart = true)
      pump { retirementEntered.count == 0L }
      allowRetirement.countDown()
      pump { context.getSharedPreferences("mux_upload",0).getString("generated_payloads_v1",null)?.contains("\"abandoned\":true") == true }
      released.complete(Unit)
      pump { chunks.size == 1 }
      upload.pause(); pump { internalInfo(upload).uploadJob!!.isCompleted }
      assertFalse(readUploadResumeState(info()).generatedResumeBlocked)
      assertFalse(readCachedUploadSnapshots().single().resumeState.generatedResumeBlocked)
      work = { chunk, _, _ -> MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
      upload.start(); pump { upload.isSuccessful && internalInfo(upload).uploadJob!!.isCompleted }
      assertEquals(2, preparations); assertEquals(2, queries)
      assertEquals(1, results.size); assertTrue(results.single().isSuccess)
    } finally { allowRetirement.countDown(); released.complete(Unit) }
  }

  @Test fun lateRetirementCannotOverwriteANewOwnersRecordOrHideItsSnapshot() {
    val target = info()
    val state = GeneratedResumeState(PayloadIdentity.capture(source)!!)
    val oldPreparation = UploadPreparationState().apply { generatedState = state; releaseBarrier = { awaitCancellation() } }
    val old = target.update(attempt = UploadAttempt(oldPreparation, MuxUpload.Progress()))
    runBlocking(Dispatchers.IO) { UploadPersistence.writeGenerated(old, state) }
    UploadPersistence.hideGenerated(old)
    writeUploadState(old, MuxUpload.Progress())
    assertFalse(readUploadResumeState(target).generatedResumeBlocked)
    assertTrue(readCachedUploadSnapshots().isEmpty())
    val output = generated(12)
    val fresh = target.update(attempt = UploadAttempt(UploadPreparationState(), MuxUpload.Progress()))
    val freshState = state.copy(phase = PreparationPhase.Validated, ownedPath = output.file.absolutePath,
      payload = output.validatedIdentity, networkStarted = true)
    runBlocking(Dispatchers.IO) {
      UploadPersistence.writeGenerated(fresh, freshState)
      UploadPersistence.scheduleGeneratedRetirement(old).join()
      UploadPersistence.retireGenerated(old)
    }
    val restored = readUploadResumeState(fresh)
    assertEquals(fresh.attempt!!.id, restored.attemptId)
    assertEquals(freshState, restored.generated); assertFalse(restored.generatedResumeBlocked)
    assertFalse(readCachedUploadSnapshots().single().resumeState.generatedResumeBlocked)
  }

  @Test fun generatedZeroProgressReconcilesAndExhaustionKeepsItsPayload() {
    val output = generated(12)
    prepare = { PreparedUpload.Generated(output) }
    query = { _, _ -> 0L }
    work = { _, _, _ -> throw GeneratedUploadRetryException("No progress") }
    val upload = MuxUpload.create(info().copy(retriesPerChunk = 2))
    upload.start(); pumpRetries { internalInfo(upload).uploadJob!!.isCompleted }
    assertTrue(upload.error is GeneratedUploadRetryException)
    assertEquals(3, chunks.size); assertEquals(3, queries)
    assertTrue(output.file.exists()); assertFalse(readUploadResumeState(info()).generatedResumeBlocked)
    work = { chunk, _, _ -> MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
    upload.start(); pump { upload.isSuccessful && internalInfo(upload).uploadJob!!.isCompleted }
    assertEquals(1, preparations); assertFalse(output.file.exists())
  }

  @Test fun confirmedForwardProgressRenewsTheBudgetUntilGeneratedUploadCompletes() {
    prepare = { PreparedUpload.Generated(generated(12)) }
    query = { _, total -> minOf((queries - 1).toLong(), total) }
    work = { _, _, _ -> throw java.io.IOException("Partial progress, lost response") }
    val upload = observe(MuxUpload.create(info().copy(retriesPerChunk = 1)))
    upload.start(); pumpRetries { upload.isSuccessful && internalInfo(upload).uploadJob!!.isCompleted }
    assertEquals((0L..11L).toList(), chunks.map { it.startByte })
    assertEquals(13, queries); assertEquals(1, results.size)
    assertEquals(12L, upload.currentProgress.bytesUploaded)
  }

  @Test fun serverRetryAfterDelaysQueriesAndChunksWithoutBlockingPause() {
    prepare = { PreparedUpload.Generated(generated(12)) }
    query = { _, _ -> if (queries == 1) throw GeneratedUploadRetryException("503", 30_000) else 0L }
    val upload = observe(MuxUpload.create(info()))
    upload.start(); pump { queries == 1 }
    dispatcher.scheduler.advanceTimeBy(29_999); dispatcher.scheduler.runCurrent()
    assertEquals(1, queries); assertTrue(chunks.isEmpty())
    dispatcher.scheduler.advanceTimeBy(1)
    work = { _, _, _ -> throw GeneratedUploadRetryException("429", 30_000) }
    pump { chunks.size == 1 }
    dispatcher.scheduler.advanceTimeBy(29_999); dispatcher.scheduler.runCurrent()
    assertEquals(2, queries); assertEquals(1, chunks.size)
    upload.pause(); pump { internalInfo(upload).uploadJob!!.isCompleted }
    dispatcher.scheduler.advanceTimeBy(60_000); dispatcher.scheduler.runCurrent()
    assertTrue(results.isEmpty()); assertEquals(2, queries)
    work = { chunk, _, _ -> MuxUpload.Progress(bytesUploaded = chunk.contentLength.toLong()) }
    upload.start(); pump { upload.isSuccessful && internalInfo(upload).uploadJob!!.isCompleted }
    assertEquals(1, preparations); assertEquals(1, results.size)
  }

  private fun pumpRetries(condition: () -> Boolean) = runBlocking {
    withTimeout(5000) {
      while (!condition()) {
        dispatcher.scheduler.advanceTimeBy(1_000)
        dispatcher.scheduler.runCurrent()
        delay(1)
      }
      dispatcher.scheduler.runCurrent()
    }
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
  @Test fun cancelledUnusedDestinationWaitsForSlowReleaseThenCanBeReused() {
    for (managed in listOf(true, false)) {
      val target = info().copy(remoteUri = Uri.parse("https://example.invalid/cancel-release-$managed"))
      val released = CompletableDeferred<Unit>()
      val partial = SdrGeneratedFile.allocate(context.cacheDir, 1)!!
      owned += partial
      prepare = { running ->
        val state = running.attempt!!.preparation.generatedState!!.copy(ownedPath = partial.file.absolutePath)
        running.attempt.preparation.generatedState = state
        running.attempt.trackCleanup(partial)
        withContext(Dispatchers.IO) { UploadPersistence.writeGenerated(running, state) }
        try { awaitCancellation() } finally {
          running.attempt.preparation.releaseBarrier = { released.await() }
        }
      }
      val upload = MuxUpload.Builder(target.remoteUri.toString(), source).manageUploadTask(managed).build()
      val before = preparations
      val beforeChunks = chunks.size
      upload.start(); pump { readUploadResumeState(target).generated?.ownedPath != null }
      upload.cancel(); pump { internalInfo(upload).uploadJob!!.isCompleted }
      runBlocking(Dispatchers.IO) { UploadPersistence.scheduleGeneratedRetirement(internalInfo(upload)).join() }
      assertFalse(readUploadResumeState(target).generatedResumeBlocked)
      assertEquals(0, queries)
      prepare = { assertFalse(partial.file.exists()); PreparedUpload.Original() }
      val fresh = MuxUpload.Builder(target.remoteUri.toString(), source).manageUploadTask(managed).build()
      fresh.start(); dispatcher.scheduler.runCurrent()
      assertEquals(before + 1, preparations); assertEquals(beforeChunks, chunks.size)
      dispatcher.scheduler.advanceTimeBy(PREPARATION_RELEASE_TIMEOUT_MS + 1)
      pump { internalInfo(fresh).uploadJob!!.isCompleted }
      assertTrue(fresh.error is PreparationReleasePendingException)
      assertNotEquals("[]", context.getSharedPreferences("mux_upload", 0).getString("generated_payloads_v1", null))
      assertFalse(readUploadResumeState(target).generatedResumeBlocked)
      released.complete(Unit)
      fresh.start(); pump { fresh.isSuccessful && internalInfo(fresh).uploadJob!!.isCompleted }
      assertEquals(before + 2, preparations); assertEquals(0, queries)
      assertFalse(readUploadResumeState(target).generatedResumeBlocked)
    }
  }

  @Test fun cleanupWinningForceRestartRaceDoesNotBlockUnusedDestination() {
    for (managed in listOf(true, false)) {
      val target = info().copy(remoteUri = Uri.parse("https://example.invalid/restart-release-$managed"))
      val released = CompletableDeferred<Unit>()
      val partial = SdrGeneratedFile.allocate(context.cacheDir, 1)!!
      owned += partial
      prepare = { running ->
        val state = running.attempt!!.preparation.generatedState!!.copy(ownedPath = partial.file.absolutePath)
        running.attempt.preparation.generatedState = state
        running.attempt.trackCleanup(partial)
        withContext(Dispatchers.IO) { UploadPersistence.writeGenerated(running, state) }
        awaitCancellation()
      }
      val upload = MuxUpload.Builder(target.remoteUri.toString(), source).manageUploadTask(managed).build()
      val before = preparations
      val beforeChunks = chunks.size
      upload.start(); pump { readUploadResumeState(target).generated?.ownedPath != null }
      upload.pause(); pump { internalInfo(upload).uploadJob!!.isCompleted }
      val old = internalInfo(upload)
      old.attempt!!.preparation.releaseBarrier = { released.await() }
      beforeCreateJob = { runBlocking(Dispatchers.IO) { UploadPersistence.scheduleGeneratedRetirement(old).join() } }
      prepare = { assertFalse(partial.file.exists()); PreparedUpload.Original() }
      upload.start(forceRestart = true); dispatcher.scheduler.runCurrent()
      assertFalse(readUploadResumeState(target).generatedResumeBlocked)
      assertEquals(before + 1, preparations); assertEquals(beforeChunks, chunks.size)
      released.complete(Unit)
      pump { upload.isSuccessful && internalInfo(upload).uploadJob!!.isCompleted }
      assertEquals(before + 2, preparations); assertEquals(0, queries)
      assertFalse(readUploadResumeState(target).generatedResumeBlocked)
      beforeCreateJob = {}
    }
  }

  @Test fun restoredAbandonedLocalPayloadIsDiscardedAndRepreparedWithoutAnOffsetQuery() {
    val output = generated(12)
    val state = GeneratedResumeState(PayloadIdentity.capture(source)!!, PreparationPhase.Validated,
      output.file.absolutePath, output.validatedIdentity, abandoned = true)
    runBlocking(Dispatchers.IO) { UploadPersistence.writeGenerated(info(), state) }
    prepare = { assertFalse(output.file.exists()); PreparedUpload.Original() }
    val upload = MuxUpload.create(info())
    upload.start(); pump { upload.isSuccessful && internalInfo(upload).uploadJob!!.isCompleted }
    assertEquals(1, preparations); assertEquals(0, queries)
    assertEquals(16L, upload.currentProgress.totalBytes)
    assertFalse(readUploadResumeState(info()).generatedResumeBlocked)
  }

  @Test fun abandonedPayloadWithPossibleRemoteBytesStillBlocksDestinationAfterRetirement() {
    val output = generated(12)
    val state = GeneratedResumeState(PayloadIdentity.capture(source)!!, PreparationPhase.Validated,
      output.file.absolutePath, output.validatedIdentity, networkStarted = true, abandoned = true)
    runBlocking(Dispatchers.IO) { UploadPersistence.writeGenerated(info(), state) }
    val upload = MuxUpload.create(info())
    upload.start(); pump { internalInfo(upload).uploadJob!!.isCompleted }
    assertTrue(upload.error is GeneratedResumeBlockedException)
    assertEquals(0, preparations); assertEquals(0, queries); assertTrue(chunks.isEmpty())
    assertFalse(output.file.exists()); assertTrue(readUploadResumeState(info()).generatedResumeBlocked)
  }

}
