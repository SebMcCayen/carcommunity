package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellRedesignNavTest {
    @Test
    fun `legacy History identity and ordinals remain separate from Chat`() {
        assertEquals(ShellTab.History, ShellTab.valueOf("History"))
        assertEquals(1, ShellTab.History.ordinal)
        assertEquals(2, ShellTab.Create.ordinal)
        assertEquals(3, ShellTab.Social.ordinal)
        assertEquals(4, ShellTab.Garage.ordinal)
        assertEquals(5, ShellTab.Chat.ordinal)
    }

    @Test
    fun `Chat pauses rendering and Back returns to Map`() {
        assertEquals(MapCover.Opaque, ShellNavigation.mapCover(ShellTab.Chat, null, false, false))
        assertEquals(ShellBackResult.GoToMapTab, ShellNavigation.onBack(ShellTab.Chat, null))
    }

    @Test
    fun `My drives is a child of Profile and a restored legacy History stays readable`() {
        val parents = ShellNavigation.pushRoute(emptyList(), ShellRoute.Profile)
        assertEquals(ShellRoute.Profile, ShellNavigation.popRoute(parents).current)
        assertEquals(MapCover.Opaque, ShellNavigation.mapCover(ShellTab.Map, ShellRoute.MyDrives, false, false))
        assertEquals(MapCover.Transparent, ShellNavigation.mapCover(ShellTab.History, null, false, false))
        assertEquals(ShellBackResult.CloseRoute, ShellNavigation.onBack(ShellTab.Map, ShellRoute.MyDrives))
    }
}
