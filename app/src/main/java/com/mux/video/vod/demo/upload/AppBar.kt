package com.mux.video.vod.demo.upload

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.mux.video.vod.demo.upload.ui.theme.MuxUploadSDKForAndroidTheme

@Composable
fun AppBar(
  navigationIcon: @Composable () -> Unit,
  navigationAction: () -> Unit,
  title: @Composable () -> Unit,
  modifier: Modifier
) {
  AppBarInner(navigationIcon = navigationIcon, navigationAction = navigationAction, modifier = modifier)
}

@Composable
fun AppBar(
  navigationIcon: @Composable () -> Unit,
  navigationAction: () -> Unit,
  modifier: Modifier
) {
  AppBarInner(navigationIcon = navigationIcon, navigationAction = navigationAction, modifier = modifier)
}


@Composable
private fun AppBarInner(
  navigationIcon: @Composable () -> Unit,
  navigationAction: () -> Unit,
  title: (@Composable () -> Unit)? = null,
  modifier: Modifier
) {

}

@Preview(showBackground = true)
@Composable
fun ListScreenPreview() {
  MuxUploadSDKForAndroidTheme {
    AppBar(
      navigationIcon = { /*TODO*/ },
      navigationAction = { /*TODO*/ },
      title = { /*TODO*/ },
      modifier = Modifier.fillMaxWidth()
    )
  }
}