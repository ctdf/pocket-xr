package com.hayeco.flipspaces.core

import com.hayeco.flipspaces.core.SpaceTasks.RootTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.6: one "FlipSpaces Space" task on the glasses. Mike's 0.5.5 dump of display 45: Task #8241
 * "FlipSpaces Space" (visible) with the Space, an empty Task #8233 "FlipSpaces Space" under a DeX
 * "Desk", and the SecondaryLauncher.
 */
class SpaceTasksTest {
    private val space = "com.hayeco.flipspaces.space/com.hayeco.flipspaces.spatial.SpaceActivity"
    private val held = RootTask(8241, childTasks = 1, activities = 0, topActivity = space, ours = true)
    private val leftover = RootTask(8233, childTasks = 0, activities = 0, topActivity = null, ours = true)
    private val desk = RootTask(8230, childTasks = 0, activities = 0, topActivity = null, ours = false)
    private val launcher = RootTask(8001, childTasks = 0, activities = 1, topActivity = "com.sec.android.app.desktoplauncher/.SecondaryLauncher", ours = false)

    @Test
    fun theLeftoverEmptySpaceTaskGoesAndNothingElse() {
        assertEquals(listOf(8233), SpaceTasks.leftovers(listOf(held, leftover, desk, launcher), keepTaskId = 8241))
    }

    @Test
    fun theHolderInUseIsNeverDeletedEvenWhenEmptyForAMoment() {
        val emptyHolder = held.copy(childTasks = 0, topActivity = null)
        assertEquals(listOf(8233), SpaceTasks.leftovers(listOf(emptyHolder, leftover), keepTaskId = 8241))
    }

    @Test
    fun anEmptyTaskThatIsNotOursStays() {
        // DeX's Desk with no window in it, or any other app's empty task.
        assertEquals(emptyList<Int>(), SpaceTasks.leftovers(listOf(desk, launcher), keepTaskId = null))
    }

    @Test
    fun aSpaceTaskWithSomethingInItStays() {
        assertEquals(emptyList<Int>(), SpaceTasks.leftovers(listOf(held, leftover.copy(activities = 1), leftover.copy(taskId = 8234, childTasks = 1)), keepTaskId = null))
    }

    @Test
    fun afterTheSweepOneSpaceTaskIsLeft() {
        val tasks = listOf(held, leftover, leftover.copy(taskId = 8102), desk, launcher)
        val gone = SpaceTasks.leftovers(tasks, keepTaskId = 8241).toSet()
        val left = tasks.filter { it.taskId !in gone && it.ours }
        assertEquals(listOf(8241), left.map { it.taskId })
    }

    @Test
    fun spaceTasksAreFoundByNameInTheDump() {
        val dump = """
            Display #45 (activities from top to bottom):
              * Task{4a1b2c3 #8241 type=standard A=10331:com.hayeco.flipspaces.space name=FlipSpaces Space mode=fullscreen translucent=false sz=1}
              * Task{77aa001 #8230 type=standard name=Desk mode=freeform translucent=true sz=0}
              * Task{9f00e1d #8233 type=standard name=FlipSpaces Space mode=fullscreen translucent=true sz=0}
              * Task{1234567 #8001 type=home A=1000:com.sec.android.app.desktoplauncher U=0 visible=true sz=1}
            RootTask taskId=8102 "FlipSpaces Space" mode=fullscreen
        """.trimIndent()
        assertEquals(setOf(8241, 8233, 8102), SpaceTasks.namedIn(dump))
        assertEquals(emptySet<Int>(), SpaceTasks.namedIn("Task{77aa001 #8230 type=standard name=Desk mode=freeform}"))
    }

    @Test
    fun bringingForwardMovesNoTaskWhenTheSpaceIsAlreadyOnTop() {
        assertFalse(SpaceTasks.needsReorder(topRootTaskId = 8241, holderTaskId = 8241))
        assertTrue(SpaceTasks.needsReorder(topRootTaskId = 8230, holderTaskId = 8241))
        assertTrue(SpaceTasks.needsReorder(topRootTaskId = null, holderTaskId = 8241))
        assertTrue(SpaceTasks.needsReorder(topRootTaskId = 8241, holderTaskId = null))
    }

    @Test
    fun twiceASecondOfPlugInChecksMakesNoTaskChanges() {
        // 0.5.5 reordered on every bring forward; with the Space on top none is needed.
        val reorders = (0 until 240).count { SpaceTasks.needsReorder(topRootTaskId = 8241, holderTaskId = 8241) }
        assertEquals(0, reorders)
    }
}
