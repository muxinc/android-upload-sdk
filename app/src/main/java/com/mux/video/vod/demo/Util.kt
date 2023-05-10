package com.mux.video.vod.demo

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil

fun Dp.toPx(context: Context?): Float {
  return if(context != null) {
    val density = context.resources.displayMetrics.density
    value * density
  } else {
    this.value
  }
}