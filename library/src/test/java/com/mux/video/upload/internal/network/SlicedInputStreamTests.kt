package com.mux.video.upload.internal.network

import com.mux.exoplayeradapter.AbsRobolectricTest
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.Test
import java.io.ByteArrayInputStream

class SlicedInputStreamTests : AbsRobolectricTest() {

  @Test
  fun testReadsUpToSliceLen() {
    val mockReadCallback = mockk<(Long) -> Unit>(relaxed = true)
    val dummyData = ByteArrayInputStream(ByteArray(150))
  }
}