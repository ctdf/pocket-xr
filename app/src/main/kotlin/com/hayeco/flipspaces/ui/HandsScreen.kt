package com.hayeco.flipspaces.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hayeco.flipspaces.core.hands.HandProviderKind
import com.hayeco.flipspaces.core.hands.ProviderStatus
import com.hayeco.flipspaces.hands.GlassesCameraProvider

/** Everything the setup screen shows, gathered by HandsActivity. */
data class HandsSetup(
    val statuses: Map<HandProviderKind, ProviderStatus>,
    val active: HandProviderKind,
    /** 1.1.0: the Pocket XR add-on, which holds the pointer service, is installed. */
    val addonInstalled: Boolean = true,
    val serviceEnabled: Boolean,
    val serviceConnected: Boolean,
    val glassesDisplayId: Int?,
    val problem: String?,
    val usbDevice: String?,
    val usbAllowed: Boolean,
    val cameraAllowed: Boolean,
    val modelReady: Boolean,
    val downloading: Boolean,
    val report: String?,
    val stats: String?,
    val preview: GlassesCameraProvider.Preview?,
    val mirror: Boolean,
    val message: String?,
    /** 0.5.0: hands start by themselves with the glasses. */
    val autoStart: Boolean = false,
    /** 0.5.1: aim with 0.5.0's shoulder ray, an option; off is the camera ray through the stable pinch point (0.5.2). */
    val shoulderRay: Boolean = false,
    /** 0.5.4: track two hands (slower) instead of one. */
    val twoHands: Boolean = false,
    /** 0.5.0: show the one time "Always open Pocket XR" hint. */
    val usbHint: Boolean = false,
    /** 0.5.0: the saved hand calibration, in words. */
    val calibration: String = "Not calibrated",
    /** 0.5.1: RECORD_AUDIO is granted, so Android offers "Always" on the plug-in prompt. */
    val micAllowed: Boolean = false,
    /** 0.5.1: what the plug-in prompt hint says now (core UsbAsk.hint). */
    val usbHintText: String = "",
)

@Composable
fun HandsScreen(
    setup: HandsSetup,
    onMessageShown: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    /** 1.1.0: opens where to get the Pocket XR add-on. */
    onGetAddon: () -> Unit = {},
    onAskCamera: () -> Unit,
    onAskUsb: () -> Unit,
    onDownloadModel: () -> Unit,
    onReadFormats: () -> Unit,
    onStartHands: () -> Unit,
    onStopHands: () -> Unit,
    onRefresh: () -> Unit,
    onAutoStartChange: (Boolean) -> Unit = {},
    onShoulderRayChange: (Boolean) -> Unit = {},
    onAllowMic: () -> Unit = {},
    onUsbHintDone: () -> Unit = {},
    onCalibrate: () -> Unit = {},
    onResetCalibration: () -> Unit = {},
    onResetAll: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(setup.message) {
        setup.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Hands", style = MaterialTheme.typography.headlineMedium) }
            // 0.5.1: first, so its reason is on screen when Android asks for the microphone.
            if (!setup.micAllowed) item { UsbHintCard(setup, onAllowMic, onUsbHintDone) }
            item { PointerServiceCard(setup, onOpenAccessibility, onOpenAppInfo, onGetAddon) }
            // 0.5.101 (Mike's settings audit): the providers list, camera formats and the shoulder ray are under Developer;
            // the mirror and Track two hands switches are gone (the glasses' camera faces out, and two hands have been
            // always on since 0.5.37); the trackpad card went to the trackpad's own Menu; the gestures are the Space's.
            item { GlassesCameraCard(setup, onAskCamera, onAskUsb, onDownloadModel, onStartHands, onStopHands) }
            if (setup.usbHint && setup.micAllowed) item { UsbHintCard(setup, onAllowMic, onUsbHintDone) }
            item { HandsAutoCard(setup, onAutoStartChange, onCalibrate, onResetCalibration) }
            item { GesturesCard() }
            item { HandsDeveloperCard(setup, onRefresh, onReadFormats, onShoulderRayChange) }
            item {
                ResetButton(
                    "Hands",
                    "Hand aim calibration is cleared (the Space offers it again) and the developer aim goes back to the camera ray. Hand setup, permissions and starting with the glasses stay.",
                    onResetAll,
                )
            }
        }
    }
}

/**
 * The plug-in prompt, the only USB grant Android keeps across unplugs. 0.5.1: Android shows its
 * "Always" box only once Pocket XR has the microphone permission (the glasses have a
 * microphone), so the card asks for that first, then says to tick Always on the single prompt.
 */
@Composable
private fun UsbHintCard(setup: HandsSetup, onAllowMic: () -> Unit, onDone: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (setup.micAllowed) "Tick Always on the plug-in prompt" else "Remember your glasses", style = MaterialTheme.typography.titleMedium)
            if (!setup.micAllowed) {
                Text(
                    "Pocket XR needs the microphone permission so Android can remember your glasses. It never records.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(setup.usbHintText, style = MaterialTheme.typography.bodySmall)
            if (setup.micAllowed) {
                TextButton(onClick = onDone) { Text("Got it") }
            } else {
                Button(onClick = onAllowMic) { Text("Allow microphone") }
            }
        }
    }
}

/** 0.5.0: hands that start by themselves, the hand ray and its calibration. */
@Composable
private fun HandsAutoCard(
    setup: HandsSetup,
    onAutoStartChange: (Boolean) -> Unit,
    onCalibrate: () -> Unit,
    onResetCalibration: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hands with the glasses", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Start hands with the glasses", Modifier.weight(1f))
                Switch(checked = setup.autoStart, onCheckedChange = onAutoStartChange)
            }
            Text(
                "Turned on the first time Start hands works. Hands then start when the Space opens on the glasses. " +
                    "Stop in the notification pauses them until the next plug-in.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "The cursor goes where the glasses camera sees your knuckles' pinch point, just in front of the " +
                    "index knuckle, about 1:1 with where you see your hand. Closing the fingers does not move it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Calibrate hand aim: ${setup.calibration}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "With the Space on the glasses, tap Calibrate and pinch on each of the five dots it shows, pointing " +
                    "at each one. It corrects the glasses camera's angle and field of view. The Space offers it once " +
                    "by itself the first time it sees your hands; an open palm skips it.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCalibrate) { Text("Calibrate hand aim") }
                OutlinedButton(onClick = onResetCalibration) { Text("Reset calibration") }
            }
        }
    }
}

@Composable
private fun PointerServiceCard(setup: HandsSetup, onOpenAccessibility: () -> Unit, onOpenAppInfo: () -> Unit, onGetAddon: () -> Unit) {
    val ok = setup.serviceConnected
    Card(
        Modifier.fillMaxWidth(),
        colors = if (ok) CardDefaults.cardColors() else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("1. Pocket XR pointer (accessibility service)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Outside the Space, hands and the trackpad reach the glasses through an accessibility service. It is the only " +
                    "way Android lets an ordinary app act on other apps' windows. It comes with the Pocket XR add-on, a " +
                    "second small app, because Google blocks installing apps with one in some countries. It:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "- draws the cursor, hints and the dock on the glasses display\n" +
                    "- reads where app windows are on the glasses (position, size and title only)\n" +
                    "- performs taps and drags on the glasses display, which is how windows are clicked, " +
                    "moved (drag on the DeX title bar) and resized (drag on a corner)\n" +
                    "It does nothing on the phone's own screens and sends nothing off the phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when {
                    !setup.addonInstalled -> "The Pocket XR add-on is not installed. The Space works without it."
                    ok && setup.glassesDisplayId != null -> "On. Cursor is on display ${setup.glassesDisplayId}."
                    ok -> "On, but no glasses display is connected."
                    setup.serviceEnabled -> "Switched on in Settings but not running yet."
                    else -> "Off. Turn on \"Pocket XR pointer\" under Settings, Accessibility, Installed apps."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            setup.problem?.let { Text("Last problem: $it", style = MaterialTheme.typography.bodySmall) }
            if (!setup.addonInstalled) {
                Button(onClick = onGetAddon) { Text("Get the add-on") }
                return@Column
            }
            Button(onClick = onOpenAccessibility) { Text("Open accessibility settings") }
            if (!ok) {
                Text(
                    "If Android greys it out (\"Restricted setting\"), open App info, use the three dot menu, " +
                        "\"Allow restricted settings\", then try again. Or by adb:",
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer {
                    Text(
                        "adb shell settings put secure enabled_accessibility_services com.hayeco.flipspaces.addon/.PointerAccess\n" +
                            "adb shell settings put secure accessibility_enabled 1",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = onOpenAppInfo) { Text("Add-on app info") }
            }
        }
    }
}

private fun describe(status: ProviderStatus?): String = when (status) {
    null -> "Checking"
    is ProviderStatus.Unavailable -> "Not available: ${status.reason}"
    is ProviderStatus.NeedsPermission -> "Needs: ${status.what}"
    ProviderStatus.Ready -> "Ready"
    is ProviderStatus.Running -> "Running: ${status.detail}"
    is ProviderStatus.Failed -> "Failed: ${status.error}"
}

@Composable
private fun GlassesCameraCard(
    setup: HandsSetup,
    onAskCamera: () -> Unit,
    onAskUsb: () -> Unit,
    onDownloadModel: () -> Unit,
    onStartHands: () -> Unit,
    onStopHands: () -> Unit,
) {
    val status = setup.statuses[HandProviderKind.GLASSES_CAMERA]
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("2. Glasses camera hand tracking", style = MaterialTheme.typography.titleMedium)
            Text(
                "Reads the standard UVC camera in the glasses over USB (no XREAL software) and finds your hand " +
                    "with Google's MediaPipe Hand Landmarker, all on the phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("USB camera: ${setup.usbDevice ?: "not found"}", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!setup.cameraAllowed) Button(onClick = onAskCamera) { Text("Allow camera") }
                if (setup.usbDevice != null && !setup.usbAllowed) Button(onClick = onAskUsb) { Text("Allow USB access") }
                if (!setup.modelReady) {
                    Button(onClick = onDownloadModel, enabled = !setup.downloading) {
                        Text(if (setup.downloading) "Downloading" else "Download hand model")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status is ProviderStatus.Running) {
                    Button(onClick = onStopHands) { Text("Stop hands") }
                } else {
                    Button(onClick = onStartHands, enabled = status == ProviderStatus.Ready || status is ProviderStatus.Failed) {
                        Text("Start hands")
                    }
                }
            }
            setup.stats?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            setup.preview?.let { CameraPreview(it, setup.mirror) }
        }
    }
}

@Composable
private fun CameraPreview(preview: GlassesCameraProvider.Preview, mirror: Boolean) {
    val bitmap = preview.frame
    Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))) {
        Image(bitmap.asImageBitmap(), contentDescription = "Glasses camera", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxSize()) {
            val box = GlassesCameraProvider.airBox
            drawRect(
                Color(0x8800E5FF),
                topLeft = Offset(box.left * size.width, box.top * size.height),
                size = androidx.compose.ui.geometry.Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
            )
            preview.hand?.landmarks?.forEach { p ->
                drawCircle(Color(0xFF5ADCFF), radius = 5f, center = Offset(p.x * size.width, p.y * size.height))
            }
        }
        if (mirror) Text("Mirrored when mapped to the glasses", Modifier.align(Alignment.BottomStart).padding(4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

/** 0.5.101: the Space's hand gestures (the DeX era's dock and title bars are gone). */
@Composable
private fun GesturesCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Hand gestures", style = MaterialTheme.typography.titleMedium)
            listOf(
                "Point: the cursor follows your hand",
                "Pinch and let go: click",
                "Pinch and hold a window's bar, move, let go: move the window",
                "Pinch and hold a corner, move, let go: resize",
                "Pinch and hold inside a window, move: scroll or drag",
                "Open palm, held a moment: the Menu",
                "Both palms up, side by side: hold every window, then push, pull or spread to move them all",
            ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** 0.5.101: what developers check, closed until opened: the input providers, the camera's formats, the shoulder ray. */
@Composable
private fun HandsDeveloperCard(setup: HandsSetup, onRefresh: () -> Unit, onReadFormats: () -> Unit, onShoulderRayChange: (Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text("Developer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (open) {
                Text("Cursor driven by: ${setup.active.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                HandProviderKind.entries.forEach { kind ->
                    Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                    Text(describe(setup.statuses[kind]), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onRefresh) { Text("Check again") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Shoulder ray (experimental): aim from the shoulder through the hand", Modifier.weight(1f))
                    Switch(checked = setup.shoulderRay, onCheckedChange = onShoulderRayChange)
                }
                if (setup.usbDevice != null && setup.usbAllowed) OutlinedButton(onClick = onReadFormats) { Text("Read camera formats") }
                setup.report?.let {
                    Text("What the camera offers:", style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
