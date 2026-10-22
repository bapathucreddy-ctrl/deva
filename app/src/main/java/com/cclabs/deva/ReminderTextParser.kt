package com.cclabs.deva
object ReminderTextParser {
    fun extract(raw:String):String{
        var text=raw.trim().replace(Regex("(?i)^hey\\s+deva[,.!?]?\\s*"),"").replace(Regex("(?i)^remind\\s+me\\s*"),"").trim()
        text=text.replace(Regex("(?i)\\s+(?:today|tomorrow)\\s+(?:morning|afternoon|evening)(?:\\s+at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)?\\s*$"),"")
        text=text.replace(Regex("(?i)\\s+(?:today|tomorrow)(?:\\s+at)?\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?\\s*$"),"")
        text=text.replace(Regex("(?i)\\s+at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?\\s*$"),"")
        text=text.replace(Regex("(?i)\\s+(?:tomorrow|today)\\s*$"),"")
        text=text.replace(Regex("(?i)^(?:today|tomorrow)\\s+(?:morning|afternoon|evening)\\s+"),"")
        text=text.replace(Regex("(?i)^(?:today|tomorrow)\\s+"),"")
        return text.removePrefix("to ").trim().ifBlank{"Reminder"}
    }
}
