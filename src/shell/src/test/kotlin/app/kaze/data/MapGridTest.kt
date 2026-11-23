package app.kaze.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapGridTest {

    @Test
    fun `bits decode lsb first`() {

        val grid = MapGrid.decode(mapId = 1, width = 2, height = 2, base64 = "Ag==")
        assertFalse(grid.isBlocked(0, 0))
        assertTrue(grid.isBlocked(1, 0))
        assertFalse(grid.isBlocked(0, 1))
        assertFalse(grid.isBlocked(1, 1))
    }

    @Test
    fun `out of bounds is never blocked`() {
        val grid = MapGrid.decode(mapId = 1, width = 2, height = 2, base64 = "Ag==")
        assertFalse(grid.isBlocked(-1, 0))
        assertFalse(grid.isBlocked(2, 0))
    }

    @Test
    fun `a larger bitmap keeps all bytes`() {

        val grid = MapGrid.decode(mapId = 2, width = 5, height = 2, base64 = "AAI=")
        assertTrue(grid.isBlocked(4, 1))
    }
}
