package com.cclabs.deva
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
class TimeParserTest {
    private val now=ZonedDateTime.of(2026,10,23,2,35,0,0,ZoneId.of("Asia/Kolkata"))
    @Test fun parsesAlarmAndReminderTimes(){
        assertEquals("Fri, 23 Oct 7:00 AM",TimeParser.parse("set an alarm for 7 AM",now)?.display)
        assertEquals("Fri, 23 Oct 6:00 PM",TimeParser.parse("remind me to call Karthik at 6 PM",now)?.display)
        assertEquals("Sat, 24 Oct 9:00 AM",TimeParser.parse("remind me tomorrow morning to pay rent",now)?.display)
        assertNotNull(TimeParser.parse("wake me tomorrow at 6:30",now))
    }
    @Test fun extractsReminderBody(){
        assertEquals("call Karthik",ReminderTextParser.extract("Remind me to call Karthik at 6 PM"))
        assertEquals("pay rent",ReminderTextParser.extract("Hey Deva, remind me tomorrow morning to pay rent"))
        assertEquals("call Mom",ReminderTextParser.extract("remind me to call Mom tomorrow at 5 pm"))
    }
}
