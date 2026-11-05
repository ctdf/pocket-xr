package com.hayeco.flipspaces

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * 1.1.15: the background update check, every 12 hours ([UpdateChecker.schedule]). It runs the same check as app start,
 * which asks GitHub only when 12 hours have passed since the last answer. Disabled in the Google Play build's manifest.
 */
class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean =
        UpdateChecker.checkIfDue(applicationContext) { jobFinished(params, false) }

    override fun onStopJob(params: JobParameters): Boolean = false
}
