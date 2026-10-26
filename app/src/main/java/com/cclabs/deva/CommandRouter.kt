package com.cclabs.deva

enum class CommandType {
    MUSIC_PLAY, MUSIC_PAUSE, MUSIC_NEXT, MUSIC_PREVIOUS,
    VOLUME_UP, VOLUME_DOWN, SET_ALARM, SET_REMINDER, OPEN_APP, TELL_TIME, GREETING, HELP, UNKNOWN
}
data class Command(val type: CommandType, val raw: String, val query: String? = null)
object CommandRouter {
    fun parse(input: String): Command {
        val normalized = input.trim().lowercase().replace(Regex("[,.!?]"), "")
        val s = normalized.removePrefix("hey deva").trim()
        return when {
            s.matches(Regex(".*\\b(pause|stop)\\b(?:.*\\b(music|song|spotify)\\b)?.*")) -> Command(CommandType.MUSIC_PAUSE, input)
            Regex("\\b(resume|continue)\\b").containsMatchIn(s) -> Command(CommandType.MUSIC_PLAY, input)
            Regex("\\bnext( track| song)?\\b").containsMatchIn(s) -> Command(CommandType.MUSIC_NEXT, input)
            Regex("\\b(previous|prev|last)( track| song)?\\b").containsMatchIn(s) -> Command(CommandType.MUSIC_PREVIOUS, input)
            Regex("\\b(volume up|increase volume|louder)\\b").containsMatchIn(s) -> Command(CommandType.VOLUME_UP, input)
            Regex("\\b(volume down|decrease volume|quieter|lower volume)\\b").containsMatchIn(s) -> Command(CommandType.VOLUME_DOWN, input)
            Regex("\\b(what time is it|tell me the time|current time|whats the time|what is the time|time now)\\b").containsMatchIn(s) -> Command(CommandType.TELL_TIME, input)
            s.matches(Regex("(hello|hi|hey|hello deva|hi deva|hey deva|good morning|good evening|good afternoon)")) -> Command(CommandType.GREETING, input)
            Regex("\\b(what can you do|help me|show commands|your commands)\\b").containsMatchIn(s) -> Command(CommandType.HELP, input)
            s.startsWith("set an alarm") || s.startsWith("set alarm") || s.startsWith("wake me") -> Command(CommandType.SET_ALARM, input)
            s.startsWith("remind me") -> Command(CommandType.SET_REMINDER, input)
            s.startsWith("open ") -> Command(CommandType.OPEN_APP, input, s.removePrefix("open ").trim())
            s.startsWith("play ") -> {
                val q = s.removePrefix("play ").removeSuffix(" on spotify").trim()
                if (q in setOf("music","song","songs")) Command(CommandType.MUSIC_PLAY, input) else Command(CommandType.MUSIC_PLAY, input, q)
            }
            else -> Command(CommandType.UNKNOWN, input)
        }
    }
}
