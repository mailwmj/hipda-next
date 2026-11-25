package net.jejer.hipda.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.SingletonImageLoader

/** Compose 树内注入的 Coil 3 [ImageLoader]；未提供时回退 [SingletonImageLoader.get]。 */
val LocalHiImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

@Composable
@ReadOnlyComposable
fun currentHiImageLoader(): ImageLoader =
    LocalHiImageLoader.current ?: SingletonImageLoader.get(LocalContext.current)
