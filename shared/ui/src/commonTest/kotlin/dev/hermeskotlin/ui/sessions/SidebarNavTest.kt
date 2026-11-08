package dev.hermeskotlin.ui.sessions

import kotlin.test.Test
import kotlin.test.assertEquals

class SidebarNavTest {

    @Test
    fun theDrawerRowKeepsInboxAndScheduledAndTheRestIsBehindMore() {
        assertEquals(listOf(SidebarPage.Inbox, SidebarPage.Scheduled), sidebarRow)
        assertEquals(
            listOf(
                SidebarPage.Insights,
                SidebarPage.Capabilities,
                SidebarPage.Plugins,
                SidebarPage.Config,
                SidebarPage.Archived,
            ),
            sidebarMore,
        )
        assertEquals(emptyList<SidebarPage>(), sidebarRow.intersect(sidebarMore.toSet()).toList())
    }
}
