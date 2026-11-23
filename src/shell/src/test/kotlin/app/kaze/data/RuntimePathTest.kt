package app.kaze.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimePathTest {

    @Test
    fun `a game process under the folder is detected`() {
        assertTrue(
            Runtime.isUnder(
                "C:\\Games\\Foo",
                "C:\\Games\\Foo\\Game.exe",
            ),
        )
        assertTrue(
            Runtime.isUnder(
                "C:\\Games\\Foo",
                "C:\\Games\\Foo\\Game.exe --type=renderer",
            ),
        )
    }

    @Test
    fun `a sibling with the same prefix is not the same game`() {
        assertFalse(
            Runtime.isUnder(
                "C:\\Games\\Foo",
                "C:\\Games\\FooBar\\Game.exe",
            ),
        )
        assertFalse(Runtime.isUnder("C:\\Games\\Foo", ""))
    }
}
