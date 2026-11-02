package com.hayeco.flipspaces.spatial

import com.hayeco.flipspaces.core.spatial.TestCardMode

/**
 * 0.5.60 (Mike: the left eye cut by a fixed vertical line on its outer edge on the Flip, not on the Aura puck): the test
 * card's switch, from the trackpad's Menu or the Space's intent extra [EXTRA]. While not [TestCardMode.OFF] the Space
 * draws [com.hayeco.flipspaces.core.spatial.TestCard] straight into its window, with no warp, in place of the scene.
 * Nothing is sent to the glasses; it is off at every start of the app.
 */
object TestCardSwitch {
    @Volatile var mode: TestCardMode = TestCardMode.OFF

    /**
     * The Space's intent extra: `adb shell am start -n com.hayeco.flipspaces/.spatial.SpaceActivity --es test_card eyes`
     * (or `full`, or `off`). The Space is singleTask, so with it already open on the glasses the extra reaches it there.
     */
    const val EXTRA = "test_card"
}
