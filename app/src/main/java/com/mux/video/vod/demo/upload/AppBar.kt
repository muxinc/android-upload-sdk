package com.mux.video.vod.demo.upload

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Icon
import androidx.compose.material.LocalContentAlpha
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.upload.ui.theme.Gray80
import com.mux.video.vod.demo.upload.ui.theme.MuxUploadSDKForAndroidTheme

@Composable
fun AppBar(
  startContent: @Composable () -> Unit,
  centerContent: @Composable () -> Unit,
  modifier: Modifier = Modifier,
) {
  AppBarInner(
    startContent = startContent,
    modifier = modifier
  )
}

@Composable
fun AppBar(
  modifier: Modifier = Modifier,
) {
  AppBarInner(modifier = modifier)
}

@Composable
fun AppBar(
  startContent: @Composable () -> Unit,
  modifier: Modifier = Modifier,
) {
  AppBarInner(
    startContent = startContent,
    modifier = modifier
  )
}

@Composable
private fun AppBarInner(
  modifier: Modifier = Modifier,
  startContent: (@Composable () -> Unit)? = null,
  centerContent: (@Composable () -> Unit)? = null,
) {
  TopAppBar(
    elevation = 0.dp,
    modifier = modifier
  ) {
    AtFullLocalAlpha {
      Box(
        modifier = Modifier.fillMaxSize(),
      ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Gray80).align(Alignment.BottomCenter))

        if (centerContent != null) {
          Box(modifier = Modifier.align(Alignment.Center)) {
            centerContent()
          }
        } else {
          Icon(
            painter = painterResource(id = R.drawable.mux_logo),
            contentDescription = "Mux Logo",
            modifier = modifier.align(Alignment.Center),
          )
        }

        if (startContent != null) {
          Box(modifier = Modifier.align(Alignment.Center)) {
            startContent()
          }
        }
      }
    }
  }
}

@Composable
private fun AtFullLocalAlpha(it: @Composable () -> Unit) {
  CompositionLocalProvider(LocalContentAlpha provides 1F) {
    it()
  }
}

@Preview(showBackground = true)
@Composable
fun Preview() {
  MuxUploadSDKForAndroidTheme(darkTheme = true) {
    AppBar(
      modifier = Modifier
    )
  }
}