package com.hayeco.flipspaces.spatial

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hayeco.flipspaces.core.spatial.HeadTrackingStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.1.14: Settings' Head tracking switch ([HeadTracker.setEnabled], [SpaceStore.headTracking]): saved, so it is still
 * off after the app starts again, and it really stops the tracker's thread and starts it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HeadTrackingSwitch1114Test {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        HeadTracker.stopForExit()
        SpaceStore(app).headTracking = true
    }

    @Test
    fun offIsSavedAndStaysOffAfterARestart() {
        assertTrue("on by default", SpaceStore(app).headTracking)
        HeadTracker.setEnabled(app, false)
        // What a new start of the app reads: the saved preference itself, and a new store.
        assertFalse(app.getSharedPreferences("space", Context.MODE_PRIVATE).getBoolean("head_tracking_1114", true))
        assertFalse(SpaceStore(app).headTracking)

        // The Space asks for head tracking after the restart: nothing is started.
        HeadTracker.acquire(app)
        assertNull(HeadTracker.worker)
        assertEquals(HeadTrackingStatus.TurnedOff, HeadTracker.status.value)
        HeadTracker.release()
        assertEquals(HeadTrackingStatus.TurnedOff, HeadTracker.status.value)
    }

    @Test
    fun theSwitchStopsTheTrackerAndStartsItAgain() {
        HeadTracker.acquire(app)
        val first = HeadTracker.worker
        assertNotNull("head tracking did not start", first)

        HeadTracker.setEnabled(app, false)
        assertNull(HeadTracker.worker)
        first!!.join(5_000)
        assertFalse("the tracker thread still runs after Off", first.isAlive)
        assertEquals(HeadTrackingStatus.TurnedOff, HeadTracker.status.value)

        HeadTracker.setEnabled(app, true)
        val second = HeadTracker.worker
        assertNotNull("head tracking did not start again", second)
        assertTrue(second!!.isAlive)
        assertNotEquals(HeadTrackingStatus.TurnedOff, HeadTracker.status.value)
        HeadTracker.release()
    }

    @Test
    fun theOldThreadsLastStatusDoesNotLandAfterOff() {
        HeadTracker.acquire(app)
        val old = HeadTracker.liveGeneration
        assertNotNull("head tracking did not start", old)
        assertTrue("the live thread's status goes through", HeadTracker.reportFrom(old!!, HeadTrackingStatus.Searching))

        HeadTracker.setEnabled(app, false)
        assertNull(HeadTracker.liveGeneration)
        // The interrupted thread, a moment after Off, writing what it was about to say.
        assertFalse(HeadTracker.reportFrom(old, HeadTrackingStatus.Searching))
        assertEquals(HeadTrackingStatus.TurnedOff, HeadTracker.status.value)

        // On again: a new thread with a new generation; the old one still writes nothing.
        HeadTracker.setEnabled(app, true)
        val new = HeadTracker.liveGeneration
        assertNotNull(new)
        assertNotEquals(old, new)
        assertFalse(HeadTracker.reportFrom(old, HeadTrackingStatus.TurnedOff))
        assertNotEquals(HeadTrackingStatus.TurnedOff, HeadTracker.status.value)
        HeadTracker.release()
    }

    @Test
    fun onWithNothingHoldingTheTrackerStartsNothing() {
        HeadTracker.setEnabled(app, false)
        HeadTracker.setEnabled(app, true)
        assertTrue(SpaceStore(app).headTracking)
        assertNull(HeadTracker.worker)
        assertEquals(HeadTrackingStatus.Off, HeadTracker.status.value)
    }
}
