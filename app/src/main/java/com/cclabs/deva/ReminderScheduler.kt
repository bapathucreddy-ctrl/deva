package com.cclabs.deva
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
object ReminderScheduler{
    fun schedule(context:Context,reminder:Reminder){
        val am=context.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,reminder.whenMillis,pendingIntent(context,reminder))
    }
    fun rescheduleAll(context:Context){
        val now=System.currentTimeMillis()
        ReminderStore(context).all().filter{it.whenMillis>now}.forEach{schedule(context,it)}
    }
    private fun pendingIntent(context:Context,reminder:Reminder):PendingIntent{
        val i=Intent(context,ReminderReceiver::class.java).putExtra("id",reminder.id).putExtra("text",reminder.text)
        return PendingIntent.getBroadcast(context,reminder.id,i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
