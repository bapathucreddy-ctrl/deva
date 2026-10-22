package com.cclabs.deva
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
class BootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action!=Intent.ACTION_BOOT_COMPLETED)return
        ReminderScheduler.rescheduleAll(context)
        val nm=context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("service","DEVA wake service",NotificationManager.IMPORTANCE_LOW))
        val pi=PendingIntent.getActivity(context,10,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        nm.notify(10,NotificationCompat.Builder(context,"service").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("DEVA needs listening restarted").setContentText("Tap to restart Hey Deva after reboot.").setContentIntent(pi).setAutoCancel(true).build())
    }
}
