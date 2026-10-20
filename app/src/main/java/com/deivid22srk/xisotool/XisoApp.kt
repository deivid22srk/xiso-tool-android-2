package com.deivid22srk.xisotool

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class XisoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_PROGRESS,
            "Operações do XISO Tool",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progresso da extração e criação de ISOs do Xbox clássico"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_PROGRESS = "xiso_operations"
        const val NOTIFICATION_PROGRESS_ID = 41
        const val NOTIFICATION_RESULT_ID = 42
    }
}
