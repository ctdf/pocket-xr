package com.hayeco.flipspaces

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hayeco.flipspaces.core.hands.CameraModel
import com.hayeco.flipspaces.core.hands.RayCalibration
import com.hayeco.flipspaces.core.hands.UsbAsk
import com.hayeco.flipspaces.hands.UsbPrompt
import com.hayeco.flipspaces.hands.GlassesCameraProvider
import com.hayeco.flipspaces.hands.HandFeed
import com.hayeco.flipspaces.hands.HandsPrefs
import com.hayeco.flipspaces.spatial.SpaceCommand
import com.hayeco.flipspaces.spatial.SpaceSession
import com.hayeco.flipspaces.hands.GlassesUsbAccess
import com.hayeco.flipspaces.hands.HandInputManager
import com.hayeco.flipspaces.hands.HandModelStore
import com.hayeco.flipspaces.hands.HandTrackingService
import com.hayeco.flipspaces.pointer.PointerHub
import com.hayeco.flipspaces.ui.FlipSpacesTheme
import com.hayeco.flipspaces.ui.HandsScreen
import com.hayeco.flipspaces.ui.HandsSetup

/**
 * M2-hands setup: what each input provider needs, the accessibility service explained, the
 * permission prompts, the glasses camera's formats and a live preview of what it sees.
 */
class HandsActivity : ComponentActivity() {
    private var refreshes by mutableIntStateOf(0)
    private var message by mutableStateOf<String?>(null)
    private var downloading by mutableStateOf(false)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    /** 0.5.1: RECORD_AUDIO, only so Android offers "Always" on the glasses' plug-in prompt. */
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        android.util.Log.i("FlipSpacesHands", "Microphone permission (for \"Always\" on the plug-in prompt) ${if (granted) "granted" else "denied"}")
        message = if (granted) {
            "Thanks. On the next plug-in, tick \"Always\" on Android's prompt."
        } else {
            "Without it Android asks at every plug-in. You can allow it here later."
        }
        refresh()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            message = if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                // Not a permission answer: the detach broadcast carries no grant extra.
                if (HandTrackingService.isRunning()) "The glasses disconnected from USB; hand tracking resumes when they are back." else null
            } else if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                "USB access to the glasses allowed."
            } else {
                "USB access to the glasses was not allowed."
            }
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ContextCompat.registerReceiver(
            this, usbReceiver,
            IntentFilter(GlassesUsbAccess.ACTION_PERMISSION).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        setContent {
            FlipSpacesTheme {
                val statuses by HandInputManager.statuses.collectAsStateWithLifecycle(emptyMap())
                val connected by PointerHub.connected.collectAsStateWithLifecycle()
                val displayId by PointerHub.displayId.collectAsStateWithLifecycle()
                val problem by PointerHub.problem.collectAsStateWithLifecycle()
                val report by GlassesCameraProvider.report.collectAsStateWithLifecycle()
                val stats by GlassesCameraProvider.stats.collectAsStateWithLifecycle()
                val preview by GlassesCameraProvider.preview.collectAsStateWithLifecycle()
                val checks = remember(refreshes, connected) { checks() }
                HandsScreen(
                    setup = HandsSetup(
                        statuses = statuses,
                        active = HandInputManager.active(statuses),
                        serviceEnabled = checks.serviceEnabled,
                        serviceConnected = connected,
                        glassesDisplayId = displayId,
                        problem = problem,
                        usbDevice = checks.usbDevice,
                        usbAllowed = checks.usbAllowed,
                        cameraAllowed = checks.cameraAllowed,
                        modelReady = checks.modelReady,
                        downloading = downloading,
                        report = report,
                        stats = stats,
                        preview = preview,
                        mirror = GlassesCameraProvider.airBox.mirror,
                        message = message,
                        autoStart = checks.autoStart,
                        shoulderRay = checks.shoulderRay,
                        twoHands = checks.twoHands,
                        usbHint = checks.usbHint,
                        calibration = checks.calibration,
                        micAllowed = checks.micAllowed,
                        usbHintText = UsbAsk.hint(checks.micAllowed),
                    ),
                    onMessageShown = { message = null },
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOpenAppInfo = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    },
                    onAskCamera = { askPermissions.launch(runtimePermissions()) },
                    onAskUsb = { GlassesUsbAccess.find(this)?.let { GlassesUsbAccess.requestPermission(this, it) } },
                    onDownloadModel = { downloadModel() },
                    onReadFormats = {
                        Thread {
                            GlassesCameraProvider.readFormats(this)
                            runOnUiThread { refresh() }
                        }.start()
                    },
                    onStartHands = { message = HandInputManager.startHands(this) ?: "Starting hand tracking on the glasses camera." },
                    onStopHands = { HandInputManager.stopHands(this) },
                    onRefresh = { refresh() },
                    onAutoStartChange = {
                        prefs.autoStart = it
                        refresh()
                    },
                    onShoulderRayChange = {
                        prefs.shoulderRay = it
                        HandFeed.resize()
                        refresh()
                    },
                    onAllowMic = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                    onUsbHintDone = {
                        prefs.usbHintShown = true
                        refresh()
                    },
                    onCalibrate = {
                        message = if (SpaceSession.active) {
                            SpaceSession.send(SpaceCommand.CalibrateHands)
                            "Look at the glasses: point at each dot and pinch. Tap again to cancel."
                        } else {
                            "Open the Space on the glasses first, with hands running."
                        }
                    },
                    onResetCalibration = {
                        prefs.calibration = RayCalibration.IDENTITY
                        prefs.calibrationRmsDeg = -1f
                        HandFeed.endCalibration(RayCalibration.IDENTITY)
                        message = "Hand calibration reset."
                        refresh()
                    },
                    // 0.5.101 (Mike: a reset to Pocket XR's defaults on each settings screen).
                    onResetAll = {
                        prefs.calibration = RayCalibration.IDENTITY
                        prefs.calibrationRmsDeg = -1f
                        prefs.calibrationOffered = false
                        prefs.shoulderRay = false
                        HandFeed.endCalibration(RayCalibration.IDENTITY)
                        HandFeed.resize()
                        message = "Hands settings are back to Pocket XR's defaults."
                        refresh()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GlassesAutoOpen.resumed(this)
        refresh()
        // 0.5.1: asked once by itself, with the reason on the card behind Android's prompt.
        if (!UsbPrompt.recordAudioGranted(this) && !prefs.micAsked) {
            prefs.micAsked = true
            // A moment to read the card's reason before Android's dialog covers it.
            window.decorView.postDelayed({
                if (!isFinishing && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            }, MIC_ASK_DELAY_MS)
        }
    }

    override fun onPause() {
        GlassesAutoOpen.paused(this)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun refresh() {
        HandInputManager.refresh(this)
        refreshes++
    }

    private data class Checks(
        val serviceEnabled: Boolean,
        val usbDevice: String?,
        val usbAllowed: Boolean,
        val cameraAllowed: Boolean,
        val modelReady: Boolean,
        val autoStart: Boolean,
        val shoulderRay: Boolean,
        val twoHands: Boolean,
        val usbHint: Boolean,
        val calibration: String,
        val micAllowed: Boolean,
    )

    private val prefs by lazy { HandsPrefs(this) }

    private companion object {
        const val MIC_ASK_DELAY_MS = 1_500L
    }

    private fun checks(): Checks {
        val device = GlassesUsbAccess.find(this)
        val fit = prefs.calibration
        val rms = prefs.calibrationRmsDeg
        return Checks(
            serviceEnabled = PointerHub.enabledInSettings(this),
            usbDevice = device?.let { GlassesUsbAccess.label(it) },
            usbAllowed = device?.let { GlassesUsbAccess.hasPermission(this, it) } == true,
            cameraAllowed = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            modelReady = HandModelStore.available(this),
            autoStart = prefs.autoStart,
            shoulderRay = prefs.shoulderRay,
            twoHands = prefs.twoHands,
            // Shown once, after hands have been started (when the plug-in prompt matters).
            usbHint = !prefs.usbHintShown && prefs.autoStart,
            calibration = if (fit.isIdentity) "not calibrated (camera defaults: ${CameraModel().line()})" else
                "%s, %.1f degrees off on its dots".format(java.util.Locale.ROOT, fit.line(), rms),
            micAllowed = UsbPrompt.recordAudioGranted(this),
        )
    }

    private fun runtimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }

    private fun downloadModel() {
        if (downloading) return
        downloading = true
        Thread {
            val error = HandModelStore.download(this)
            runOnUiThread {
                downloading = false
                message = error ?: "Hand model downloaded."
                refresh()
            }
        }.start()
    }
}
