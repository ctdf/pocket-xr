package com.hayeco.flipspaces.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.1.11 (Google Play): the add-on's prominent disclosures ([Disclosure]) and who offers GitHub's updates ([UpdateCheck]). */
class Disclosure1111Test {
    @Test
    fun onlyAnAffirmativeTapOpensSettings() {
        assertEquals(Disclosure.Step.OPEN_SETTINGS, Disclosure.after(agreed = true))
        assertEquals(Disclosure.Step.STAY_OFF, Disclosure.after(agreed = false))
    }

    @Test
    fun eachDisclosureSaysWhatItReadsWhyAndThatNothingLeaves() {
        for (kind in Disclosure.Kind.entries) {
            val t = Disclosure.text(kind)
            assertTrue(kind.name, t.reads.isNotEmpty())
            assertTrue(kind.name, t.use.isNotBlank())
            assertTrue(kind.name, t.sharing.contains("not") || t.sharing.contains("Nothing"))
            assertTrue(kind.name, t.sharing.contains("sent off the phone"))
            assertTrue(kind.name, t.withoutIt.startsWith("Without it"))
        }
        val pointer = Disclosure.full(Disclosure.Kind.POINTER)
        assertTrue(pointer.contains("AccessibilityService API"))
        assertTrue(pointer.contains("not an accessibility tool"))
        assertTrue(pointer.contains("window title"))
        assertTrue(Disclosure.full(Disclosure.Kind.NOTES).contains("notification"))
    }

    @Test
    fun theTextsHaveNoEmDashes() {
        for (kind in Disclosure.Kind.entries) assertFalse(Disclosure.full(kind).contains('—'))
        assertFalse(Disclosure.AGREE.contains('—'))
    }

    @Test
    fun copiesFromPlayOrFDroidNeverOfferGitHubUpdates() {
        assertFalse(UpdateCheck.selfUpdates("com.android.vending"))
        assertFalse(UpdateCheck.selfUpdates("org.fdroid.fdroid"))
        assertTrue(UpdateCheck.selfUpdates(null))
        assertTrue(UpdateCheck.selfUpdates("com.google.android.packageinstaller"))
        assertTrue(UpdateCheck.fromPlay("com.android.vending"))
        assertFalse(UpdateCheck.fromPlay("com.sec.android.app.samsungapps"))
    }
}
