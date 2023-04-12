package com.mux.video.vod.demo.util

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable

@Composable
fun MiddleIconTopBar(
  startWidget: @Composable () -> Unit,
  middleWidget: @Composable () -> Unit,
  endWidget: @Composable () -> Unit
) {
  return Row {
    startWidget()
    middleWidget()
    endWidget()
  }
}