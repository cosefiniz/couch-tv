package app.couchtv.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(Notes.CH_TV, getString(R.string.ch_tv), NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(Notes.CH_UPDATE, getString(R.string.ch_update), NotificationManager.IMPORTANCE_DEFAULT))
        }
        CheckJob.schedule(this)
    }
}
