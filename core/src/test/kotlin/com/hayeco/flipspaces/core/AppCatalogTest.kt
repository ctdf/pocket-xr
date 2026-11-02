package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCatalogTest {
    private val apps = listOf(
        AppEntry("YouTube", "com.google.android.youtube", "Main"),
        AppEntry("chrome", "com.android.chrome", "Main"),
        AppEntry("Pocket XR", "com.hayeco.flipspaces", "MainActivity"),
        AppEntry("Calendar", "com.samsung.android.calendar", "Main"),
        AppEntry("Calendar", "com.samsung.android.calendar", "Main"),
    )

    @Test
    fun sortsCaseInsensitivelyAndDropsSelfAndDuplicates() {
        val labels = AppCatalog.prepare(apps, "com.hayeco.flipspaces").map { it.label }
        assertEquals(listOf("Calendar", "chrome", "YouTube"), labels)
    }

    @Test
    fun filtersByLabelOrPackage() {
        assertEquals(listOf("YouTube"), AppCatalog.prepare(apps, "x", " you ").map { it.label })
        assertEquals(listOf("Calendar"), AppCatalog.prepare(apps, "x", "samsung").map { it.label })
    }
}
