package com.cclabs.deva
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
class ReminderReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val nm=context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("reminders","DEVA reminders",NotificationManager.IMPORTANCE_HIGH))
        val id=intent.getIntExtra("id",1)
        val text=intent.getStringExtra("text")?:"Reminder"
        val n=NotificationCompat.Builder(context,"reminders").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("DEVA reminder").setContentText(text).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        nm.notify(id,n); ReminderStore(context).remove(id)
    }
}
