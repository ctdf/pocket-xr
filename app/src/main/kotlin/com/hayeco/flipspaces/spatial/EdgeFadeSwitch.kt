package com.hayeco.flipspaces.spatial

import com.hayeco.flipspaces.core.spatial.EdgeFadeMode

/**
 * 0.5.61 (Mike: the left eye's "wall" is the left eye picture's own pixel 0): the edge fade's choice, from the trackpad's
 * Menu or the Space's intent extra [EXTRA], saved in [SpaceStore.edgeFade] ([EdgeFadeMode.SOFT] until chosen). The
 * renderer reads it every frame; the store only once.
 */
object EdgeFadeSwitch {
    @Volatile private var mode: EdgeFadeMode? = null

    fun mode(store: SpaceStore): EdgeFadeMode = mode ?: store.edgeFade.also { mode = it }

    fun set(store: SpaceStore, m: EdgeFadeMode) {
        mode = m
        store.edgeFade = m
    }

    /**
     * The Space's intent extra: `adb shell am start -n com.hayeco.flipspaces/.spatial.SpaceActivity --es edge_fade soft`
     * (or `wide`, or `off`); saved as the Menu's choice is.
     */
    const val EXTRA = "edge_fade"
}
