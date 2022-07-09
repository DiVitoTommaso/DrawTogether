package com.divito.drawtogether.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.divito.drawtogether.R

private fun createNotificationChannel(ctx: Context) {
    // create channel
    Log.i("WhiteboardActivity", "Notification channel created")
    with(ctx) {
        val name = getString(R.string.channel_name)
        val descriptionText = getString(R.string.channel_description)
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(packageName, name, importance).apply {
            description = descriptionText
        }

        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

fun postNotification(ctx: Context, id: Int, msg: String) {
    // post notification
    Log.i("WhiteboardActivity", "Notification posted")
    with(ctx) {
        createNotificationChannel(ctx)
        val builder = NotificationCompat.Builder(this, packageName)
            .setSmallIcon(R.mipmap.ic_app_icon)
            .setContentTitle("Whiteboard bluetooth")
            .setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        NotificationManagerCompat.from(this).notify(id, builder.build())
    }
}

class NotificationService : Service() {

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val builder = NotificationCompat.Builder(this, packageName)
            .setSmallIcon(R.mipmap.ic_app_icon)
            .setContentTitle("Whiteboard bluetooth")
            .setContentText("An active connection is still running")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("An active connection is still running.\n Return to app or it'll be closed due to inactivity"))
            .setAutoCancel(true)

        startForeground(5, builder.build())

        // service was restarted due to application kill
        Log.i("NotificationService", "Foreground service started")
        if(flags != 0 && intent != null) {
            postNotification(this, 1, "${intent.extras!!.get("other") as String} disconnected")
            stopSelf()
        }

        return START_REDELIVER_INTENT
    }
}