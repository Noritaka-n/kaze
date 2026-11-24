package app.kaze

import app.kaze.data.Engine
import app.kaze.data.Game
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CliLogicTest {
    private fun game(id: String) = Game(
        id = id,
        title = id,
        subtitle = "",
        engine = Engine.RMMZ,
        sourceLang = "ja",
        monogram = id.take(1),
    )

    @Test
    fun `launch without an id uses the first game`() {
        val games = listOf(game("a"), game("b"))
        assertEquals("a", selectLaunchGame(games, null)?.id)
        assertEquals("b", selectLaunchGame(games, "b")?.id)
    }

    @Test
    fun `launch with an unknown id does not fall back to another game`() {
        assertNull(selectLaunchGame(listOf(game("a"), game("b")), "missing"))
        assertNull(selectLaunchGame(emptyList(), null))
    }
}
