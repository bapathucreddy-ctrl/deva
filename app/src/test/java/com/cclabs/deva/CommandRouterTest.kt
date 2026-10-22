package com.cclabs.deva
import org.junit.Assert.assertEquals
import org.junit.Test
class CommandRouterTest {
    @Test fun parsesTargetCommands() {
        assertEquals(CommandType.MUSIC_PLAY, CommandRouter.parse("Hey Deva, play Prabhas songs").type)
        assertEquals("prabhas songs", CommandRouter.parse("Hey Deva, play Prabhas songs").query)
        assertEquals(CommandType.MUSIC_PAUSE, CommandRouter.parse("pause music").type)
        assertEquals(CommandType.MUSIC_NEXT, CommandRouter.parse("next track").type)
        assertEquals(CommandType.MUSIC_PREVIOUS, CommandRouter.parse("previous track").type)
        assertEquals(CommandType.VOLUME_UP, CommandRouter.parse("volume up").type)
        assertEquals(CommandType.VOLUME_DOWN, CommandRouter.parse("volume down").type)
        assertEquals(CommandType.SET_ALARM, CommandRouter.parse("set an alarm for 7 AM").type)
        assertEquals(CommandType.SET_REMINDER, CommandRouter.parse("remind me to call Karthik at 6 PM").type)
        assertEquals(CommandType.OPEN_APP, CommandRouter.parse("open spotify").type)
    }
}
