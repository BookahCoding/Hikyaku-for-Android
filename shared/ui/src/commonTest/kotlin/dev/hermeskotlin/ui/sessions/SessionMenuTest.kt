package dev.hermeskotlin.ui.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionMenuTest {

    @Test
    fun theOpenChatsMenuPutsTheTaskAboveTheChat() {
        val groups = sessionMenuGroups(SessionMenuKind.entries.toSet())
        assertEquals("This task", groups[0].title)
        assertEquals(
            listOf(SessionMenuKind.Usage, SessionMenuKind.Processes, SessionMenuKind.Checkpoints, SessionMenuKind.Goal),
            groups[0].items,
        )
        assertEquals("This chat", groups[1].title)
        assertEquals(
            listOf(
                SessionMenuKind.StartFresh,
                SessionMenuKind.Rename,
                SessionMenuKind.MakeThread,
                SessionMenuKind.Pin,
                SessionMenuKind.Export,
                SessionMenuKind.CopyId,
                SessionMenuKind.Archive,
                SessionMenuKind.Delete,
            ),
            groups[1].items,
        )
    }

    @Test
    fun aRowWithOnlyChatActionsHasNoHeadings() {
        val groups = sessionMenuGroups(setOf(SessionMenuKind.Rename, SessionMenuKind.Pin, SessionMenuKind.Archive, SessionMenuKind.Delete))
        assertEquals(1, groups.size)
        assertNull(groups[0].title)
        assertEquals(
            listOf(SessionMenuKind.Rename, SessionMenuKind.Pin, SessionMenuKind.Archive, SessionMenuKind.Delete),
            groups[0].items,
        )
    }
}
