package com.mux.video.vod.demo.upload.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.material.primarySurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.google.accompanist.systemuicontroller.rememberSystemUiController

private val DarkColorPalette = darkColors(
  primary = Green50,
  primaryVariant = Green60,
  secondary = Pink40,
  background = BackgroundDark,
  surface = Gray100,
  onBackground = White,
  onPrimary = White,
  onSecondary = White,
  onSurface = White,
)

private val LightColorPalette = lightColors(
  primary = Green60,
  primaryVariant = Green70,
  secondary = Pink40,
  surface = Gray100,
  onSurface = White,
  onBackground = BackgroundDark,
  onPrimary = White,
  onSecondary = White,
)

@Composable
fun MuxUploadSDKForAndroidTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit
) {
  val systemUiController = rememberSystemUiController()
  val colors = if (darkTheme) {
    DarkColorPalette
  } else {
    LightColorPalette
  }

  DisposableEffect(systemUiController, darkTheme) {
    systemUiController.setStatusBarColor(
      color = if (darkTheme) {
        colors.primarySurface
      } else {
        colors.primaryVariant
      },
      darkIcons = false
    )
    onDispose { }
  }

  MaterialTheme(
    colors = colors,
    typography = Typography,
    shapes = Shapes,
    content = content
  )
}