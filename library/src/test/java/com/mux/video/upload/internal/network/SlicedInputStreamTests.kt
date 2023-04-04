package com.mux.video.upload.internal.network

import com.mux.exoplayeradapter.AbsRobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class SlicedInputStreamTests : AbsRobolectricTest() {

  @Test
  fun testReadsBuffersUpToSliceLen() {
    val dummyData = ByteArrayInputStream(ByteArray(101))

    val slice = dummyData.sliceOf(50)
    val readBytes = slice.read(ByteArray(60)) // 10 bytes bigger than the slice
    assertEquals(
      50,
      readBytes
    )
    assertEquals(
      dummyData.available(),
      51
    )
  }

  @Test
  fun testReadsToOffsetUpToSliceLen() {
    val dummyData = ByteArrayInputStream(ByteArray(101))

    val firstSlice = dummyData.sliceOf(50)
    val firstRead = firstSlice.read(ByteArray(60), 5, 50) // 10 bytes bigger than the slice
    assertEquals(
      50,
      firstRead
    )
    assertEquals(
      dummyData.available(),
      51
    )
  }

  @Test
  fun testReadsWithOffsetNotEnoughReadBuffer() {
    val dummyData = ByteArrayInputStream(ByteArray(101))

    val firstSlice = dummyData.sliceOf(50)
    val firstRead = firstSlice.read(ByteArray(60), 40, 10)
    assertEquals(
      10,
      firstRead
    )
    assertEquals(
      dummyData.available(),
      91
    )
  }

  @Test
  fun testReadsWholeBufferNotEnoughReadBuffer() {
    val dummyData = ByteArrayInputStream(ByteArray(101))

    val firstSlice = dummyData.sliceOf(50)
    val firstRead = firstSlice.read(ByteArray(10))
    assertEquals(
      10,
      firstRead
    )
    assertEquals(
      dummyData.available(),
      91
    )
  }
}