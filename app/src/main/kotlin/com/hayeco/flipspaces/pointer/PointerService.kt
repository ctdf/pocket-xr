package com.hayeco.flipspaces.pointer

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.hayeco.flipspaces.GlassesAutoOpen
import com.hayeco.flipspaces.GlassesHomeActivity
import com.hayeco.flipspaces.SpacesLauncher
import com.hayeco.flipspaces.SpacesState
import com.hayeco.flipspaces.core.AppCatalog
import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.Bounds
import com.hayeco.flipspaces.core.DisplayCandidate
import com.hayeco.flipspaces.core.DisplaySelector
import com.hayeco.flipspaces.core.FreeformSetup
import com.hayeco.flipspaces.core.hands.HandProviderKind
import com.hayeco.flipspaces.core.hands.HandStatus
import com.hayeco.flipspaces.core.pointer.PointerAction
import com.hayeco.flipspaces.core.pointer.PointerController
import com.hayeco.flipspaces.core.pointer.PointerSignal
import com.hayeco.flipspaces.core.pointer.PressKind
import com.hayeco.flipspaces.core.spatial.DexFullScreenButton
import com.hayeco.flipspaces.core.spatial.SeenButton
import com.hayeco.flipspaces.core.windows.WindowChrome
import com.hayeco.flipspaces.core.windows.WindowFrame
import com.hayeco.flipspaces.core.windows.WindowLimits
import com.hayeco.flipspaces.platform.AppLauncher
import com.hayeco.flipspaces.platform.DisplayMonitor
import com.hayeco.flipspaces.platform.InstalledApps
import com.hayeco.flipspaces.platform.WindowingProbe
import com.hayeco.flipspaces.spatial.SpaceSession
import kotlin.math.roundToInt

/**
 * Pocket XR pointer: the accessibility service that turns pointer signals from any provider
 * (hands, trackpad) into a cursor on the glasses and real touches there.
 *
 * - Finds the glasses display with the same DisplaySelector as the launcher.
 * - Draws [CursorOverlayView] in a TYPE_ACCESSIBILITY_OVERLAY window on that display, through
 *   createDisplayContext(...).createWindowContext(...) (API 30).
 * - Reads app window bounds on that display from getWindowsOnAllDisplays() (API 30).
 * - Runs core's PointerController and performs its actions with [GestureInjector]: taps, live
 *   drags, and one-shot drags on DeX's title bars and corners to move and resize windows.
 */
class PointerService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var monitor: DisplayMonitor
    private lateinit var injector: GestureInjector
    private lateinit var installedApps: InstalledApps

    private var glasses: DisplayCandidate? = null
    private var controller: PointerController? = null
    private var overlay: CursorOverlayView? = null
    private var overlayManager: WindowManager? = null

    private var windowsCache: List<WindowFrame> = emptyList()
    private var windowsAtMs = 0L
    private var source = HandProviderKind.TRACKPAD
    private var catalog: List<AppEntry> = emptyList()

    /**
     * 0.5.19 ("Overlay on display 35 failed", token null, as the glasses' 3D
     * switch replaced the display): a display change is acted on once the changes have settled for
     * [SETTLE_MS]; a removal still at once, so the cursor never outlives its display.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshSoon()
        override fun onDisplayRemoved(displayId: Int) {
            main.removeCallbacks(settled)
            refreshDisplay()
        }
        override fun onDisplayChanged(displayId: Int) = refreshSoon()
    }

    private val settled = Runnable { refreshDisplay() }

    private fun refreshSoon() {
        main.removeCallbacks(settled)
        main.postDelayed(settled, SETTLE_MS)
    }

    /** 0.5.19: an overlay refused for a token not valid yet is tried again once. */
    private var overlayRetried = false

    private val ticker = object : Runnable {
        override fun run() {
            val c = controller ?: return
            if (!c.busy) return
            perform(c.tick(SystemClock.uptimeMillis()))
            main.postDelayed(this, TICK_MS)
        }
    }

    private val hideDock = Runnable { overlay?.showDock(emptyList()) }

    /** 0.5.0: the Space draws its own hand cursors; the overlay hides its own while it is up. */
    private val spaceListener: (Boolean) -> Unit = { up ->
        main.post {
            overlay?.spaceUp = up
            // 0.5.16: the full screen window no longer sits over the Space.
            Log.i(TAG, if (up) "Cursor window hidden while the Space is up (shown only for a banner, the dock or a preview)" else "Cursor window shown: the Space is down")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        monitor = DisplayMonitor(this)
        installedApps = InstalledApps(this)
        injector = GestureInjector(this) { problem ->
            PointerHub.report(problem)
            overlay?.flash(problem)
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        PointerHub.attach(this)
        SpaceSession.activeListeners += spaceListener
        // 0.4.2: while switched on, this service opens the Space on plug-in with no Pocket XR screen in front.
        GlassesAutoOpen.attachHost(this)
        refreshDisplay()
        Thread { catalog = AppCatalog.prepare(installedApps.load(), packageName) }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) windowsAtMs = 0
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        PointerHub.detach(this)
        SpaceSession.activeListeners -= spaceListener
        GlassesAutoOpen.detachHost(this)
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        main.removeCallbacks(settled)
        removeOverlay()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    fun onSignal(signal: PointerSignal, from: HandProviderKind) {
        val c = controller ?: run {
            if (signal is PointerSignal.Down || (signal as? PointerSignal.ForHand)?.signal is PointerSignal.Down) PointerHub.report("No glasses display, so there is nowhere to click.")
            return
        }
        source = from
        perform(c.handle(signal, SystemClock.uptimeMillis(), windows()))
        if (c.busy) {
            main.removeCallbacks(ticker)
            main.postDelayed(ticker, TICK_MS)
        }
    }

    fun onHandStatus(status: HandStatus?, from: HandProviderKind) {
        overlay?.showHand(status, from.label)
    }

    private fun perform(actions: List<PointerAction>) {
        val view = overlay
        for (action in actions) {
            when (action) {
                is PointerAction.Cursor -> view?.showCursor(action.at, action.hover, action.pressed)
                is PointerAction.Tap -> tap(action)
                is PointerAction.DragStart -> injector.start(action.at)
                is PointerAction.DragTo -> injector.moveTo(action.at)
                is PointerAction.DragEnd -> injector.end(action.at)
                is PointerAction.Preview -> view?.showPreview(action.bounds)
                PointerAction.ClearPreview -> view?.showPreview(null)
                is PointerAction.Commit -> {
                    Log.i(TAG, "${action.hit.zone} ${action.hit.window.title}: ${action.hit.window.bounds} -> ${action.bounds}, drag ${action.from} -> ${action.to}")
                    injector.drag(action.from, action.to)
                    windowsAtMs = 0
                }
                PointerAction.ShowDock -> showDock()
                PointerAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK).also { Log.i(TAG, "Back from ${source.label}: $it") }
                is PointerAction.PressDecided -> {
                    val what = when (action.kind) {
                        PressKind.TAP -> "tap"
                        PressKind.LONG_PRESS -> "long press"
                        PressKind.DRAG -> "drag"
                    }
                    val by = if (action.pinch) "pinch (${when (action.rightHand) { true -> "right"; false -> "left"; null -> "unknown" }} hand)" else "press"
                    Log.i(TAG, "Press from ${source.label}: $by held ${action.heldMs} ms, tap limit ${action.limitMs} ms: $what")
                }
            }
        }
    }

    private fun tap(action: PointerAction.Tap) {
        val view = overlay
        if (view != null && view.dockVisible) {
            val item = view.dockItemAt(action.at)
            val onDock = view.dockContains(action.at)
            view.showDock(emptyList())
            main.removeCallbacks(hideDock)
            if (item != null) {
                item.onPick()
                return
            }
            if (onDock) return
        }
        val home = if (GlassesHomeActivity.runningOn(injector.displayId) != null) "Pocket XR home is on it" else "Pocket XR home is not on it"
        Log.i(TAG, "Tap from ${source.label} at ${action.at.x.roundToInt()},${action.at.y.roundToInt()} on display ${injector.displayId} ($home)")
        injector.tap(action.at)
    }

    /** App windows on the glasses, topmost layer last, cached until the next windows change. */
    private fun windows(): List<WindowFrame> {
        val id = glasses?.id ?: return emptyList()
        val now = SystemClock.uptimeMillis()
        if (now - windowsAtMs < WINDOWS_TTL_MS) return windowsCache
        val rect = Rect()
        windowsCache = runCatching { windowsOnAllDisplays.get(id).orEmpty() }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .map { w ->
                w.getBoundsInScreen(rect)
                WindowFrame(w.id, Bounds(rect.left, rect.top, rect.right, rect.bottom), w.title?.toString(), w.layer)
            }
            .filter { !it.bounds.isEmpty }
        windowsAtMs = now
        return windowsCache
    }

    /**
     * 0.5.9: DeX's own full screen for the Space. Reads every window on [displayId] (the caption is
     * the desktop shell's window, not the app's), picks the caption's full screen button
     * ([DexFullScreenButton]) and clicks it; when the click action is refused, taps its centre with a
     * gesture on that display instead. Main thread. The answer says what it did or what it saw.
     */
    fun pressDexFullScreen(displayId: Int): String {
        val windows = runCatching { windowsOnAllDisplays.get(displayId).orEmpty() }.getOrElse { return "not pressed: windows on display $displayId unreadable (${it.javaClass.simpleName}: ${it.message})" }
        if (windows.isEmpty()) return "not pressed: no windows readable on display $displayId"
        val rect = Rect()
        val seen = mutableListOf<Pair<SeenButton, android.view.accessibility.AccessibilityNodeInfo>>()
        for (w in windows) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            val queue = ArrayDeque(listOf(root))
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val n = queue.removeFirst()
                visited++
                val label = (n.contentDescription ?: n.text ?: n.tooltipText)?.toString().orEmpty()
                if (label.isNotBlank()) {
                    n.getBoundsInScreen(rect)
                    seen += SeenButton(label, rect.left, rect.top, rect.right, rect.bottom, n.isClickable, w.title?.toString().orEmpty()) to n
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
            }
        }
        val buttons = seen.map { it.first }
        val pick = DexFullScreenButton.pick(buttons) ?: return "not pressed: no full screen button in DeX's title bar on display $displayId (saw ${DexFullScreenButton.describe(buttons)})"
        val node = seen.first { it.first === pick }.second
        // The node itself, else its nearest clickable parent (the caption may label an icon inside a button).
        var target: android.view.accessibility.AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        val clicked = target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
        if (clicked) return "pressed DeX's \"${pick.label}\" button at ${pick.left},${pick.top} on display $displayId (window \"${pick.windowTitle}\")"
        val cx = (pick.left + pick.right) / 2f
        val cy = (pick.top + pick.bottom) / 2f
        val path = android.graphics.Path().apply { moveTo(cx, cy) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
            .setDisplayId(displayId)
            .build()
        val sent = dispatchGesture(gesture, null, null)
        return if (sent) "tapped DeX's \"${pick.label}\" button at %.0f,%.0f on display $displayId (its click action was refused)".format(cx, cy)
        else "not pressed: DeX's \"${pick.label}\" button refused the click and the tap could not be sent"
    }

    private fun refreshDisplay() {
        val chosen = DisplaySelector.select(monitor.snapshot(), SpacesState.choices.value.preferredDisplayId)
        if (chosen?.id == glasses?.id && chosen?.widthPx == glasses?.widthPx && chosen?.heightPx == glasses?.heightPx && overlay != null) return
        glasses = chosen
        removeOverlay()
        PointerHub.displayChanged(chosen?.id)
        if (chosen == null) {
            controller = null
            return
        }
        injector.displayId = chosen.id
        val bounds = Bounds(0, 0, chosen.widthPx, chosen.heightPx)
        val scale = chosen.densityDpi.coerceAtLeast(120) / 160f
        fun px(dp: Int) = (dp * scale).roundToInt()
        val insets = SpacesState.hosting.value.insets[chosen.id]
        controller = PointerController(
            display = bounds,
            chrome = WindowChrome.forDensity(chosen.densityDpi),
            limits = WindowLimits(
                minWidthPx = px(220),
                minHeightPx = px(160),
                topInsetPx = insets?.topPx ?: 0,
                bottomInsetPx = insets?.bottomPx ?: px(48),
                keepVisiblePx = px(80),
                edgeGuardPx = px(4),
            ),
        )
        addOverlay(chosen)
    }

    private fun addOverlay(target: DisplayCandidate) {
        val display = monitor.display(target.id) ?: return
        try {
            val context = createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = context.getSystemService(WindowManager::class.java)
            val view = CursorOverlayView(context, context.resources.displayMetrics.density)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                title = "Pocket XR cursor"
            }
            wm.addView(view, params)
            overlay = view
            overlayManager = wm
            view.spaceUp = SpaceSession.active
            controller?.cursor?.let { view.showCursor(it, null, false) }
            PointerHub.report(null)
            overlayRetried = false
        } catch (e: WindowManager.BadTokenException) {
            // 0.5.19: the display was being replaced; quietly, once more after it settles.
            if (!overlayRetried) {
                overlayRetried = true
                Log.i(TAG, "cursor overlay on display ${target.id} not added yet (${e.message}); trying again once the display settles")
                glasses = null
                refreshSoon()
            } else {
                Log.w(TAG, "cursor overlay on display ${target.id} refused again: ${e.message}")
                PointerHub.report("Could not draw the cursor on the glasses: ${e.message}")
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Overlay on display ${target.id} failed", e)
            PointerHub.report("Could not draw the cursor on the glasses: ${e.message}")
        }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        runCatching { overlayManager?.removeViewImmediate(view) }
        overlay = null
        overlayManager = null
    }

    /** Open palm or three finger tap: Spaces home, the apps placed so far, then the first apps A to Z. */
    private fun showDock() {
        val view = overlay ?: return
        val display = glasses ?: return
        Thread {
            val iconPx = (52 * resources.displayMetrics.density).roundToInt()
            val placed = SpacesState.choices.value.occupied.values.toList()
            val apps = (placed + catalog).distinctBy { it.key }.take(DOCK_APPS)
            val items = listOf(CursorOverlayView.DockItem("Pocket XR home", null) { openHome(display) }) +
                apps.map { app -> CursorOverlayView.DockItem(app.label, installedApps.icon(app, iconPx)) { launch(app, display) } }
            main.post {
                view.showDock(items)
                main.removeCallbacks(hideDock)
                main.postDelayed(hideDock, DOCK_MS)
            }
        }.start()
    }

    private fun openHome(display: DisplayCandidate) {
        val failure = AppLauncher.openGlassesHome(this, display.id)
        overlay?.flash(failure?.let { "Could not open Pocket XR home: ${it.message}" } ?: "Pocket XR home")
    }

    private fun launch(app: AppEntry, display: DisplayCandidate) {
        val home = GlassesHomeActivity.runningOn(display.id)
        val message = if (home != null) {
            SpacesLauncher.launchFromHome(home, app, display, FreeformSetup.evaluate(WindowingProbe.read(this)))
        } else {
            val failure = AppLauncher.openGlassesHome(this, display.id)
            if (failure == null) {
                SpacesState.queue(SpacesState.PendingLaunch(app, display.id, SystemClock.elapsedRealtime()))
                "Opening Pocket XR home, then ${app.label}."
            } else {
                "Could not open ${app.label}: ${failure.message}"
            }
        }
        overlay?.flash(message)
    }

    companion object {
        private const val TAG = "FlipSpacesPointer"
        private const val TICK_MS = 30L

        /** 0.5.19: display changes settle this long before the cursor overlay follows them. */
        private const val SETTLE_MS = 400L
        private const val WINDOWS_TTL_MS = 250L
        private const val DOCK_MS = 8000L
        private const val DOCK_APPS = 9

        /** 0.5.9: at most this many nodes read per window when looking for DeX's full screen button. */
        private const val MAX_NODES = 400
    }
}
