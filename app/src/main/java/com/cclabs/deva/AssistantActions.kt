package com.cclabs.deva

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.content.ComponentName
import androidx.core.app.NotificationManagerCompat
import android.net.Uri
import android.provider.AlarmClock
import android.view.KeyEvent
import java.time.Instant
import java.time.ZoneId

class AssistantActions(private val context: Context) {
    enum class FollowUp { CONFIRM_YOUTUBE_MUSIC }
    data class Result(val spoken: String, val success: Boolean, val followUp: FollowUp? = null)

    fun execute(command: Command): Result = when (command.type) {
        CommandType.MUSIC_PLAY -> if (command.query.isNullOrBlank()) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Playing music.") else searchSpotify(command.query)
        CommandType.MUSIC_PAUSE -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused.")
        CommandType.MUSIC_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Next track.")
        CommandType.MUSIC_PREVIOUS -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Previous track.")
        CommandType.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE, "Volume up.")
        CommandType.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER, "Volume down.")
        CommandType.SET_ALARM -> setAlarm(command.raw)
        CommandType.SET_REMINDER -> setReminder(command.raw)
        CommandType.OPEN_APP -> openApp(command.query.orEmpty())
        CommandType.TELL_TIME -> tellTime()
        CommandType.GREETING -> Result("Hi bro! What can I do for you?", true)
        CommandType.HELP -> Result("I can open Spotify, play or pause music, skip tracks, change volume, tell the time, and set alarms or reminders.", true)
        CommandType.UNKNOWN -> Result("I didn't understand that yet.", false)
    }

    private fun tellTime(): Result {
        val now = java.time.LocalTime.now()
        val formatted = now.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH))
        return Result("It's " + formatted + ".", true)
    }

    fun openYouTubeMusic(): Result {
        val intent = context.packageManager.getLaunchIntentForPackage(YOUTUBE_MUSIC_PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return Result("YouTube Music isn't installed.", false)
        return try { context.startActivity(intent); Result("Opening YouTube Music.", true) }
        catch (_: Exception) { Result("I couldn't open YouTube Music.", false) }
    }

    private fun mediaKey(code: Int, reply: String): Result {
        if (!NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) {
            return Result("Enable DEVA notification access in settings to control music without opening Spotify.", false)
        }
        return try {
            val manager = context.getSystemService(MediaSessionManager::class.java)
            val listener = ComponentName(context, DevaMediaListener::class.java)
            val sessions = manager.getActiveSessions(listener)
            val spotify = sessions.firstOrNull { it.packageName == SPOTIFY_PACKAGE }
            val player = spotify ?: sessions.firstOrNull()
                ?: return Result("Start Spotify playback once, then I can pause, resume, and skip songs.", false)
            val transport = player.transportControls
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> transport.play()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> transport.pause()
                KeyEvent.KEYCODE_MEDIA_NEXT -> transport.skipToNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> transport.skipToPrevious()
                else -> return Result("That media command is not supported.", false)
            }
            Result(reply, true)
        } catch (e: SecurityException) {
            Result("DEVA needs notification access to control Spotify.", false)
        } catch (e: Exception) {
            Result("I couldn't control the current music session.", false)
        }
    }

    private fun volume(direction: Int, reply: String): Result {
        context.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return Result(reply, true)
    }

    private fun searchSpotify(query: String): Result {
        if (context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE) == null) {
            return Result("Spotify isn't installed. Would you like me to open YouTube Music?", false, FollowUp.CONFIRM_YOUTUBE_MUSIC)
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}")).setPackage(SPOTIFY_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); Result("Showing $query in Spotify. Playing an exact song in the background needs Spotify account authorization.", false) }
        catch (_: Exception) { Result("Spotify couldn't open that search. Would you like me to open YouTube Music?", false, FollowUp.CONFIRM_YOUTUBE_MUSIC) }
    }

    private fun setAlarm(raw: String): Result {
        val parsed = TimeParser.parse(raw) ?: return Result("Tell me a time for the alarm.", false)
        val zoned = Instant.ofEpochMilli(parsed.epochMillis).atZone(ZoneId.systemDefault())
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, zoned.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, zoned.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "DEVA alarm")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return Result("I couldn't find a Clock app that accepts alarm requests.", false)
        return try {
            context.startActivity(intent)
            Result("Alarm request sent for ${zoned.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))}.", true)
        } catch (_: Exception) { Result("I couldn't send that alarm to the Clock app.", false) }
    }

    private fun setReminder(raw: String): Result {
        val parsed = TimeParser.parse(raw) ?: return Result("Tell me when to remind you.", false)
        val reminder = ReminderStore(context).add(ReminderTextParser.extract(raw), parsed.epochMillis)
        ReminderScheduler.schedule(context, reminder)
        return Result("Reminder saved.", true)
    }

    private fun openApp(name: String): Result {
        val pkg = when {
            name.contains("spotify", ignoreCase = true) -> SPOTIFY_PACKAGE
            name.contains("youtube music", ignoreCase = true) -> YOUTUBE_MUSIC_PACKAGE
            else -> null
        } ?: return Result("I can open Spotify or YouTube Music in this version.", false)
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return Result("That app isn't installed.", false)
        return try {
            context.startActivity(intent)
            Result("Opening ${if (pkg == SPOTIFY_PACKAGE) "Spotify" else "YouTube Music"}.", true)
        } catch (_: Exception) { Result("I couldn't open that app.", false) }
    }

    companion object {
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
    }
}
