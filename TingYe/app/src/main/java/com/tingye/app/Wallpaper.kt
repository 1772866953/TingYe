package com.tingye.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

internal data class WallpaperChoice(val id: String, val label: String, val colors: List<Color>)

internal val wallpaperChoices = listOf(
    WallpaperChoice("sage", "山间晨光", listOf(Color(0xFF29483C), Color(0xFF759384), Color(0xFFD8DFCC))),
    WallpaperChoice("dusk", "暮色海岸", listOf(Color(0xFF283450), Color(0xFF777A99), Color(0xFFE3BAA2))),
    WallpaperChoice("night", "夜读", listOf(Color(0xFF172426), Color(0xFF38534F), Color(0xFF9AA69A)))
)

@Composable internal fun WallpaperSurface(mode: String, uri: String?, modifier: Modifier = Modifier,
    scrim: Color = Color.Black, scrimAlpha: Float = 0.22f, blurRadius: Float = 0f) {
    val colors = wallpaperChoices.firstOrNull { it.id == mode }?.colors ?: wallpaperChoices.first().colors
    val brush = remember(mode) { Brush.verticalGradient(colors) }
    val image = if (mode == "custom") rememberImage(uri, 1600) else null
    Box(modifier) {
        Box(Modifier.fillMaxSize().then(if (image != null && blurRadius > 0f) Modifier.blur(blurRadius.dp) else Modifier).background(brush)) {
            if (image != null) Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(scrim.copy(alpha = scrimAlpha)))
    }
}
