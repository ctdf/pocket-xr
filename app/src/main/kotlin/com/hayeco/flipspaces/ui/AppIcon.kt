package com.hayeco.flipspaces.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.hayeco.flipspaces.core.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

typealias IconLoader = (AppEntry, Int) -> Bitmap?

@Composable
fun AppIcon(app: AppEntry, size: Dp, loader: IconLoader, modifier: Modifier = Modifier) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<Bitmap?>(null, app.key, sizePx) {
        value = withContext(Dispatchers.IO) { loader(app, sizePx) }
    }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(
            modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(size / 4)),
        )
    }
}
