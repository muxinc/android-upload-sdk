package com.mux.video.upload.internal

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.mux.exoplayeradapter.AbsRobolectricTest
import com.mux.video.upload.api.MuxUpload
import io.mockk.*
import junit.framework.TestCase.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class UploadPersistenceTests : AbsRobolectricTest() {

  @Test
  fun testForget() {
    initializeUploadPersistence(mockContext())
    val uploadInfoInA = uploadInfo("file/a")
    val stateInA = MuxUpload.Progress(
      bytesUploaded = 5,
      totalBytes = 10,
      startTime = 1,
      updatedTime = 2
    )
    val uploadInfoInB = uploadInfo("file/b")
    val stateInB = MuxUpload.Progress(
      bytesUploaded = 2,
      totalBytes = 4,
      startTime = 1,
      updatedTime = 2
    )

    writeUploadState(uploadInfoInA, stateInA)
    writeUploadState(uploadInfoInB, stateInB)
    forgetUploadState(uploadInfoInA)
    val uploadsOut = readAllCachedUploads()

    assertEquals(
      "There should be 1 upload read out",
      1,
      uploadsOut.size
    )
    val uploadOutA = uploadsOut.find { it.inputFile.absolutePath == uploadInfoInA.inputFile.absolutePath }
    assertNull(
      "file A should NOT be in the output",
      uploadOutA
    )
    val uploadOutB = uploadsOut.find { it.inputFile.absolutePath == uploadInfoInB.inputFile.absolutePath }
    assertNotNull(
      "upload B should be in the output",
      uploadOutB,
    )
  }

  @Test
  fun testReadAll() {
    initializeUploadPersistence(mockContext())
    val uploadInfoInA = uploadInfo("file/a")
    val stateInA = MuxUpload.Progress(
      bytesUploaded = 5,
      totalBytes = 10,
      startTime = 1,
      updatedTime = 2
    )
    val uploadInfoInB = uploadInfo("file/b")
    val stateInB = MuxUpload.Progress(
      bytesUploaded = 2,
      totalBytes = 4,
      startTime = 1,
      updatedTime = 2
    )

    writeUploadState(uploadInfoInA, stateInA)
    writeUploadState(uploadInfoInB, stateInB)
    val uploadsOut = readAllCachedUploads()

    assertEquals(
      "There should be 2 uploads read out",
      2,
      uploadsOut.size
    )
    val uploadOutA = uploadsOut.find { it.inputFile.absolutePath == uploadInfoInA.inputFile.absolutePath }
    assertNotNull(
      "file A should be in the output",
      uploadOutA
    )
    assertEquals(
      "upload A should be read as written",
      uploadInfoInA.update(restoredFromOriginal = true),
      uploadOutA
    )
  }

  @Test
  fun testReadLastByte() {
    initializeUploadPersistence(mockContext())
    val uploadInfoInA = uploadInfo("file/a")
    val stateInA = MuxUpload.Progress(
      bytesUploaded = 5,
      totalBytes = 10,
      startTime = 1,
      updatedTime = 2
    )
    val uploadInfoInB = uploadInfo("file/b")
    val stateInB = MuxUpload.Progress(
      bytesUploaded = 2,
      totalBytes = 4,
      startTime = 1,
      updatedTime = 2
    )

    writeUploadState(uploadInfoInA, stateInA)
    writeUploadState(uploadInfoInB, stateInB)

    val lastByteA = readLastByteForFile(uploadInfoInA)
    assertEquals(
      "last byte should be read from the correct file",
      5,
      lastByteA
    )
    val lastByteB = readLastByteForFile(uploadInfoInB)
    assertEquals(
      "last byte should be read from the correct file",
      2,
      lastByteB
    )
  }

  @Test
  fun cachedSnapshotsReadEntriesAndBlocksOnceForAllUploads() {
    val prefs = mockSharedPreferences()
    initializeUploadPersistence(mockContext(prefs))
    writeUploadState(uploadInfo("file/a"), MuxUpload.Progress(bytesUploaded = 5, totalBytes = 10))
    writeUploadState(uploadInfo("file/b"), MuxUpload.Progress(bytesUploaded = 2, totalBytes = 4))
    clearMocks(prefs, answers = false, childMocks = false)
    val snapshots = readCachedUploadSnapshots()
    assertEquals(listOf(5L, 2L), snapshots.map { it.resumeState.bytesSent })
    assertEquals(listOf(true, true), snapshots.map { it.resumeState.paused })
    verify(exactly = 1) { prefs.getString("uploads", null) }
    verify(exactly = 1) { prefs.getString("generated_upload_blocks", null) }
  }

  private fun uploadInfo(name: String = "a/file") = UploadInfo(
    inputFile = File(name).absoluteFile,
    remoteUri = Uri.parse("https://www.mux.com/$name"),
    chunkSize = 2,
    retriesPerChunk = 3,
    optOut = false,
    uploadJob = null,
    statusFlow = null,
  )

  private fun mockContext(prefs: SharedPreferences = mockSharedPreferences()): Context {
    return mockk<Context> {
      every { applicationContext } returns mockk {
        every { getSharedPreferences(any(), any()) } returns prefs
      }
    }
  }

  private fun mockSharedPreferences(): SharedPreferences {
    val map = mutableMapOf<String, String>()
    return mockk<SharedPreferences> {
      val keySlot = slot<String>()
      every { getString(capture(keySlot), any()) } answers { map[keySlot.captured] }
      every { edit() } returns mockk {
        val fakeThis = this
        val keySlotEdit = slot<String>()
        val valueSlotEdit = slot<String>()
        every { putString(capture(keySlotEdit), capture(valueSlotEdit)) } answers {
          map[keySlotEdit.captured] = valueSlotEdit.captured
          fakeThis
        }
        every { apply() } just runs
      }
    }
  }

}
