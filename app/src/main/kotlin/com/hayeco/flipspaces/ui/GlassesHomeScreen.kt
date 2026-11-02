package com.hayeco.flipspaces.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hayeco.flipspaces.SpacesState
import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.spatial.FlatBanner
import com.hayeco.flipspaces.core.spatial.FlatFix
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Flat mode home surface on the glasses: clock, slot map, an app dock along the bottom and,
 * since 0.4.0, a banner saying why this is not the Space, with its one-tap fix.
 */
@Composable
fun GlassesHomeScreen(
    displaySummary: String,
    choices: SpacesState.Choices,
    apps: List<AppEntry>,
    status: String?,
    banner: FlatBanner?,
    iconLoader: IconLoader,
    onLaunch: (AppEntry) -> Unit,
    onFix: (FlatFix) -> Unit,
) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(15_000)
        }
    }
    Box(Modifier.fillMaxSize().padding(32.dp)) {
        Column(Modifier.align(Alignment.TopStart)) {
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(now), style = MaterialTheme.typography.displayMedium)
            Text("Pocket XR", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(displaySummary, style = MaterialTheme.typography.bodySmall)
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            banner?.let { FlatBannerCard(it, onFix, Modifier.width(560.dp).padding(top = 12.dp)) }
        }
        SlotMap(
            grid = SpacesState.grid,
            selected = choices.selectedSlot,
            occupied = choices.occupied,
            onSelect = null,
            modifier = Modifier.align(Alignment.TopEnd).width(360.dp),
        )
        LazyRow(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            items(apps, key = { it.key }) { app ->
                Column(
                    Modifier.width(72.dp).clickable { onLaunch(app) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppIcon(app, 48.dp, iconLoader)
                    Spacer(Modifier.height(4.dp))
                    Text(app.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
