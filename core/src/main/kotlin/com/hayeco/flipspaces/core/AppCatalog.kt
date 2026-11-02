package com.hayeco.flipspaces.core

/** A launchable activity from the launcher query. */
data class AppEntry(val label: String, val packageName: String, val activityName: String) {
    val key: String get() = "$packageName/$activityName"
}

object AppCatalog {
    /** Sorted by label, own package removed, duplicates dropped, optionally filtered by [query]. */
    fun prepare(apps: List<AppEntry>, ownPackage: String, query: String = ""): List<AppEntry> {
        val needle = query.trim().lowercase()
        return apps.asSequence()
            .filter { it.packageName != ownPackage }
            .distinctBy { it.key }
            .filter {
                needle.isEmpty() || needle in it.label.lowercase() || needle in it.packageName.lowercase()
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it: AppEntry -> it.label }.thenBy { it.packageName })
            .toList()
    }
}
