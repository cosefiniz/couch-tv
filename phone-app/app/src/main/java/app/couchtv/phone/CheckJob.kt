package app.couchtv.phone

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Every 15 minutes, only on Wi-Fi (unmetered): ask the stick for notifications and updates. */
class CheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { Notes.check(this) } finally { jobFinished(params, false) }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 5401

        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            if (!Hub.notificationsOn(c)) { js.cancel(ID); return }
            if (js.allPendingJobs.any { it.id == ID }) return
            js.schedule(JobInfo.Builder(ID, ComponentName(c, CheckJob::class.java))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .build())
        }

        fun reschedule(c: Context) {
            c.getSystemService(JobScheduler::class.java).cancel(ID)
            schedule(c)
        }
    }
}

/** After a reboot or an update: make sure the check is scheduled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = CheckJob.schedule(c)
}
