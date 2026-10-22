package com.cclabs.deva
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
data class Reminder(val id:Int,val text:String,val whenMillis:Long)
class ReminderStore(context:Context){
    private val prefs=context.getSharedPreferences("deva_reminders",Context.MODE_PRIVATE)
    fun all():List<Reminder>{
        val arr=JSONArray(prefs.getString("items","[]")?:"[]")
        return buildList{
            for(i in 0 until arr.length()){
                val o=arr.getJSONObject(i)
                add(Reminder(o.getInt("id"),o.getString("text"),o.getLong("when")))
            }
        }.sortedBy{it.whenMillis}
    }
    fun add(text:String,whenMillis:Long):Reminder{
        val r=Reminder(((System.currentTimeMillis()%Int.MAX_VALUE).toInt()).let{if(it<0)-it else it},text,whenMillis)
        save(all()+r); return r
    }
    fun remove(id:Int)=save(all().filterNot{it.id==id})
    private fun save(items:List<Reminder>){
        val arr=JSONArray(); items.forEach{arr.put(JSONObject().put("id",it.id).put("text",it.text).put("when",it.whenMillis))}
        prefs.edit().putString("items",arr.toString()).apply()
    }
}
