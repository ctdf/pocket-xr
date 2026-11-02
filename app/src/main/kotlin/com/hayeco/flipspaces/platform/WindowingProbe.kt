package com.hayeco.flipspaces.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.hayeco.flipspaces.core.FreeformSetup
import com.hayeco.flipspaces.core.WindowingEnvironment

/** Reads the freeform and desktop mode switches. Call again on resume: adb changes them live. */
object WindowingProbe {
    fun read(context: Context): WindowingEnvironment {
        val resolver = context.contentResolver
        fun global(key: String): Int? = try {
            // Unset means the platform default, which is off for all of these.
            Settings.Global.getString(resolver, key)?.trim()?.toIntOrNull() ?: 0
        } catch (_: SecurityException) {
            null
        }
        return WindowingEnvironment(
            sdkInt = Build.VERSION.SDK_INT,
            hasFreeformFeature = context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT),
            enableFreeformSupport = global(FreeformSetup.enableFreeform.key),
            forceResizableActivities = global(FreeformSetup.forceResizable.key),
            forceDesktopModeOnExternalDisplays = global(FreeformSetup.desktopOnExternal.key),
        )
    }

    /**
     * Settings.Secure mirror_built_in_display (Android 16 external display settings): 1 means
     * external displays mirror the phone. Null when unset or not readable by apps.
     */
    fun mirrorBuiltInDisplay(context: Context): Boolean? = try {
        Settings.Secure.getString(context.contentResolver, "mirror_built_in_display")?.trim()?.toIntOrNull()?.let { it == 1 }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    const val DEX_PACKAGE = "com.sec.android.desktopmode.uiservice"

    fun dexInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(DEX_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
