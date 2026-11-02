package com.hayeco.flipspaces.ui

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import com.hayeco.flipspaces.R

/**
 * 0.5.68 (Mike: one bundled font, so the glasses and the phone match whatever the phone's own font is set to): Inter
 * (SIL Open Font License, docs/LICENSES.md) in the two weights [com.hayeco.flipspaces.core.Look] uses, Regular and
 * Medium. Loaded once at app start ([init]); before that, or if the font cannot load, the system sans-serif.
 */
object LookType {
    @Volatile var regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        private set

    @Volatile var medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private set

    fun init(context: Context) {
        try {
            regular = context.resources.getFont(R.font.inter_regular)
            medium = context.resources.getFont(R.font.inter_medium)
        } catch (e: Exception) {
            Log.w("FlipSpaces", "Inter did not load; the system sans-serif stays", e)
        }
    }
}
