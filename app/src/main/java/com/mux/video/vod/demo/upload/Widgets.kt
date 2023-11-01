package com.mux.video.vod.demo.upload

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Button
import androidx.compose.material.Icon
import androidx.compose.material.LocalContentAlpha
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.toPx
import com.mux.video.vod.demo.upload.ui.theme.Gray80
import com.mux.video.vod.demo.upload.ui.theme.MuxUploadSDKForAndroidTheme

@Composable
fun MuxAppBar(
  modifier: Modifier = Modifier,
  startContent: @Composable () -> Unit,
  centerContent: @Composable () -> Unit,
) {
  AppBarInner(
    startContent = startContent,
    centerContent = centerContent,
    modifier = modifier
  )
}

@Composable
fun MuxAppBar(
  modifier: Modifier = Modifier,
) {
  AppBarInner(modifier = modifier)
}

@Composable
fun MuxAppBar(
  modifier: Modifier = Modifier,
  startContent: @Composable () -> Unit,
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
  Box(
    modifier = modifier
  ) {
    TopAppBar(
      elevation = 0.dp,
      modifier = Modifier
    ) {
      AtFullAlpha {
        Box(
          modifier = Modifier.fillMaxSize(),
        ) {

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
            Box(
              modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(16.dp)
            ) {
              startContent()
            }
          }
        }
      }
    }
    Box(
      Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(Gray80)
        .align(Alignment.BottomCenter)
    )
  }
}

@Composable
fun DefaultButton(
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
  content: @Composable () -> Unit
) {
  Button(
    onClick = onClick,
    modifier = modifier
      .height(40.dp)
  ) {
    content()
  }
}

@Composable
fun CreateUploadCta(
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  val ctx = LocalContext.current
  val contentColor = MaterialTheme.colors.onBackground
  val dashStrokeStyle = Stroke(
    width = 1.dp.toPx(ctx),
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(ctx), 8.dp.toPx(ctx)), 0f)
  )
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = modifier
      .fillMaxWidth()
      .height(THUMBNAIL_SIZE)
      .background(MaterialTheme.colors.background)
      .drawBehind {
        drawRoundRect(
          color = contentColor,
          cornerRadius = CornerRadius(12.dp.toPx(ctx)),
          style = dashStrokeStyle
        )
      }
      .clickable { onClick() }
  ) {
    Icon(
      painter = painterResource(id = R.drawable.ic_add),
      contentDescription = "",
      tint = MaterialTheme.colors.onBackground
    )
    Spacer(
      modifier = Modifier.size(8.dp)
    )
    Text(
      text = "Tap to upload a Video",
      fontWeight = FontWeight.W700,
      color = contentColor
    )
  }
}

@Composable
private fun AtFullAlpha(it: @Composable () -> Unit) {
  CompositionLocalProvider(LocalContentAlpha provides 1F) {
    it()
  }
}

@Preview(showBackground = true)
@Composable
fun Preview() {
  MuxUploadSDKForAndroidTheme(darkTheme = true) {
    MuxAppBar(
      modifier = Modifier
    )
  }
}

val THUMBNAIL_SIZE = 228.dp