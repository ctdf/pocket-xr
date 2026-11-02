package com.hayeco.flipspaces.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import com.hayeco.flipspaces.core.AppEntry

/** Lists apps with a launcher icon and loads their icons on demand. */
class InstalledApps(context: Context) {
    private val pm = context.applicationContext.packageManager
    private val icons = LruCache<String, Bitmap>(200)

    fun load(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map {
            AppEntry(
                label = it.loadLabel(pm).toString(),
                packageName = it.activityInfo.packageName,
                activityName = it.activityInfo.name,
            )
        }
    }

    /** Blocking; call off the main thread. */
    fun icon(app: AppEntry, sizePx: Int): Bitmap? {
        icons.get(app.key)?.let { return it }
        return try {
            pm.getActivityIcon(ComponentName(app.packageName, app.activityName))
                .toBitmap(sizePx, sizePx)
                .also { icons.put(app.key, it) }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
