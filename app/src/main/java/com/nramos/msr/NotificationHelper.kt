package com.nramos.msr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

object NotificationHelper {
    private const val CHANNEL_ID = "screen_recording_channel"

    //actual notification that will show on top of our screen
    fun createNotification(context: Context): Notification {
        val intent = Intent(context, MainActivity::class.java) //want to launch our main activity
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        ) //when we tap the notification we launch the above intent

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Screen recording")
            .setContentText("Recording in progress...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recording Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val notificationManager = context.getSystemService<NotificationManager>()
            notificationManager?.createNotificationChannel(serviceChannel)
        }

    }
}