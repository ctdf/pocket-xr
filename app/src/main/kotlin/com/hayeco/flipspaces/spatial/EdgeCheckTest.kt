package com.hayeco.flipspaces.spatial

/**
 * 0.5.27: the edge check's switch, from the trackpad's Menu. While on, the Space draws
 * [com.hayeco.flipspaces.core.spatial.EdgeCheck]'s frame and ticks on each eye's picture. Nothing is sent to the
 * glasses; it is off at every start.
 */
object EdgeCheckTest {
    @Volatile var on: Boolean = false

    /** 0.5.29: both eyes, or only L, or only R (the other eye's half black). */
    @Volatile var eyes: com.hayeco.flipspaces.core.spatial.EdgeEyes = com.hayeco.flipspaces.core.spatial.EdgeEyes.BOTH

    /**
     * 0.5.27: counts each change of the per-eye picture adjust saved by a trackpad ([SpaceStore.pictureAdjust]); the
     * Space reads the saved adjust again when it moves.
     */
    @Volatile var adjustVersion: Int = 0
}
