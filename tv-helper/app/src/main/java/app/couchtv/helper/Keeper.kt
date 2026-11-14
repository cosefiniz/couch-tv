package app.couchtv.helper

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Keeps the stick's way into the TV open.
 *
 * This TV forgets Wireless debugging at every full restart (power cut, plug pulled, deep standby
 * that ends in a reboot), and while it is off the stick cannot reach the TV at all: no phone
 * remote keys, no profile picking, no couch-tv brought back. So after each start, once there is a
 * network, this switches Wireless debugging back on, exactly as the switch in Developer options
 * does. The stick then finds it over mDNS within seconds, connects with the key the TV already
 * trusts (no code) and reopens port 5555.
 *
 * Needs WRITE_SECURE_SETTINGS, granted once over ADB (`pm grant app.couchtv.helper
 * android.permission.WRITE_SECURE_SETTINGS`); without it nothing here runs. The stick can switch it
 * off with `CONFIG --ez keep_adb false`.
 */
object Keeper {
    private const val TAG = "couchtv.keeper"
    private const val JOB_NOW = 71
    private const val JOB_PERIODIC = 72
    const val CHANNEL_KEY = "app.couchtv.helper/app.couchtv.helper.ChannelKey"

    fun canWrite(c: Context) =
        c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun wanted(c: Context) = c.getSharedPreferences("helper", 0).getBoolean("keep_adb", true)

    /** Schedules the check: now (as soon as there is a network) and every 15 minutes after. */
    fun kick(c: Context, why: String) {
        if (!canWrite(c) || !wanted(c)) return
        // Never let this take the app down: the red button and the seek bar live in the same process.
        runCatching {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            val svc = ComponentName(c, AdbJob::class.java)
            js.schedule(JobInfo.Builder(JOB_NOW, svc).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
            if (js.getPendingJob(JOB_PERIODIC) == null) {
                js.schedule(JobInfo.Builder(JOB_PERIODIC, svc)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true)
                    .build())
            }
            Log.i(TAG, "check scheduled ($why)")
        }.onFailure {
            Log.w(TAG, "could not schedule the check ($why): $it")
            Thread { ensureAdbWifi(c) }.start()   // at least look once now
        }
    }

    /** Turns Wireless debugging on if it is off. True when it is on afterwards. */
    fun ensureAdbWifi(c: Context): Boolean {
        if (!canWrite(c) || !wanted(c)) return false
        val cr = c.contentResolver
        if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) return true
        return runCatching {
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            Log.i(TAG, "Wireless debugging was off: switched it on")
            true
        }.getOrElse { Log.w(TAG, "could not switch Wireless debugging on: $it"); false }
    }

    /**
     * Android drops an app's accessibility service whenever the app is updated. If the red-button
     * service was on before, put it back (the person at the TV switched it on once; an update
     * should not undo that).
     */
    fun restoreChannelKey(c: Context) {
        if (!canWrite(c)) return
        if (!c.getSharedPreferences("helper", 0).getBoolean("channel_key_on", false)) return
        runCatching {
            val cr = c.contentResolver
            val list = (Settings.Secure.getString(cr, "enabled_accessibility_services") ?: "")
                .split(':').filter { it.isNotEmpty() && it != "null" }
            if (CHANNEL_KEY !in list) {
                Settings.Secure.putString(cr, "enabled_accessibility_services", (list + CHANNEL_KEY).joinToString(":"))
                Log.i(TAG, "switched the red-button service back on")
            }
            Settings.Secure.putInt(cr, "accessibility_enabled", 1)
        }.onFailure { Log.w(TAG, "could not restore the red-button service: $it") }
    }
}

/**
 * Right after a start the network may not be up yet, and Android switches Wireless debugging off
 * again if it is set before Wi-Fi has connected. So for the first two and a half minutes it looks
 * every 5 seconds, until it has stayed on three times in a row.
 */
class AdbJob : JobService() {
    @Volatile private var stop = false

    override fun onStartJob(p: JobParameters): Boolean {
        stop = false
        Thread {
            runCatching {
                val end = System.currentTimeMillis() + 150_000
                var steady = 0
                while (!stop && System.currentTimeMillis() < end && steady < 3) {
                    val wasOn = Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
                    Keeper.ensureAdbWifi(this)
                    steady = if (wasOn) steady + 1 else 0
                    if (steady < 3) Thread.sleep(5000)
                }
            }
            jobFinished(p, false)
        }.start()
        return true
    }

    override fun onStopJob(p: JobParameters): Boolean {
        stop = true
        return true
    }
}

/** After a start or an update. */
class Boot : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED) Keeper.restoreChannelKey(c)
        Keeper.kick(c, i.action ?: "boot")
    }
}
