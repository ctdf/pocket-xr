package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.1: the Space's own task on Android 17 (createTask) and before (createRootTask). */
class TaskHoldApiTest {
    /** ITaskOrganizerController on the Flip's Android 17 framework.jar (dexdump). */
    private val android17 = listOf(
        "createTask" to 1, "deleteTask" to 1, "getRootTasks" to 2, "getChildTasks" to 2,
        "registerTaskOrganizer" to 1, "unregisterTaskOrganizer" to 1, "getImeLayeringTarget" to 1,
    )

    /** Android 15 and 16. */
    private val android15 = listOf(
        "createRootTask" to 4, "deleteRootTask" to 1, "getRootTasks" to 2, "getChildTasks" to 2,
    )

    @Test
    fun android17UsesCreateTask() {
        val api = TaskHoldApi.pick(android17, 37)
        assertEquals(TaskApi.CREATE_TASK, api)
        assertEquals("deleteTask", api.delete)
        assertEquals("createTask and deleteTask on Android 37", TaskHoldApi.line(api, android17, 37))
    }

    @Test
    fun android15And16KeepCreateRootTask() {
        assertEquals(TaskApi.CREATE_ROOT_TASK, TaskHoldApi.pick(android15, 35))
        assertEquals(TaskApi.CREATE_ROOT_TASK, TaskHoldApi.pick(android15 + ("createRootTask" to 3), 36))
        assertEquals("deleteRootTask", TaskApi.CREATE_ROOT_TASK.delete)
    }

    @Test
    fun aBuildWithBothPrefersTheOneForItsVersion() {
        val both = android15 + android17
        assertEquals(TaskApi.CREATE_ROOT_TASK, TaskHoldApi.pick(both, 36))
        assertEquals(TaskApi.CREATE_TASK, TaskHoldApi.pick(both, 37))
    }

    @Test
    fun noneSaysWhatTheControllerHas() {
        val odd = listOf("createTaskSomehow" to 2, "getRootTasks" to 2)
        val api = TaskHoldApi.pick(odd, 38)
        assertEquals(TaskApi.NONE, api)
        val line = TaskHoldApi.line(api, odd, 38)
        assertTrue(line, line.contains("createTaskSomehow(2)") && line.contains("Android 38"))
    }

    @Test
    fun aFreeformWindowAfterTheHoldMovesToAPresentation() {
        assertEquals(SpaceSurface.PRESENTATION, SpaceSurfacePlan.choose(27, freeform = true, holdSettled = true))
        // Not before the hold has had its say, not when it worked, never on the phone.
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(27, freeform = true, holdSettled = false))
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(27, freeform = false, holdSettled = true))
        assertEquals(SpaceSurface.WINDOW, SpaceSurfacePlan.choose(0, freeform = true, holdSettled = true, forced = true))
        assertEquals(SpaceSurface.PRESENTATION, SpaceSurfacePlan.choose(2, freeform = false, holdSettled = false, forced = true))
    }
}
