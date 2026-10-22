package com.cclabs.deva
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
data class ParsedTime(val epochMillis: Long, val display: String)
object TimeParser {
    private val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)
    fun parse(text: String, now: ZonedDateTime = ZonedDateTime.now()): ParsedTime? {
        val lower = text.lowercase(Locale.ENGLISH)
        val m = timeRegex.findAll(lower).toList().lastOrNull() ?: return when {
            "morning" in lower -> build(now,9,0,lower)
            "afternoon" in lower -> build(now,15,0,lower)
            "evening" in lower -> build(now,19,0,lower)
            else -> null
        }
        var hour=m.groupValues[1].toIntOrNull()?:return null
        val minute=m.groupValues[2].toIntOrNull()?:0
        val ap=m.groupValues[3].lowercase()
        if(minute !in 0..59 || hour !in 0..23) return null
        if(ap.isNotEmpty()){
            if(hour !in 1..12) return null
            if(ap=="pm" && hour!=12) hour+=12
            if(ap=="am" && hour==12) hour=0
        }
        return build(now,hour,minute,lower)
    }
    private fun build(now: ZonedDateTime,hour:Int,minute:Int,text:String):ParsedTime{
        var day=now.toLocalDate()
        if("tomorrow" in text) day=day.plusDays(1)
        var target=ZonedDateTime.of(day, LocalTime.of(hour,minute),now.zone)
        if("tomorrow" !in text && !target.isAfter(now)) target=target.plusDays(1)
        val f=DateTimeFormatter.ofPattern("EEE, d MMM h:mm a",Locale.ENGLISH)
        return ParsedTime(target.toInstant().toEpochMilli(),target.format(f))
    }
}
