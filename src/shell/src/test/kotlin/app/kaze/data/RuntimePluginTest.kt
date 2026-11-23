package app.kaze.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimePluginTest {

    @Test
    fun `removing the bridge keeps plugins added later`() {
        val root = Files.createTempDirectory("kaze-plugin").toFile()
        File(root, "js/plugins").mkdirs()
        File(root, "js/plugins/KazeBridge.js").writeText("// bridge")

        val plugins = File(root, "js/plugins.js")
        plugins.writeText(
            """
            var ${'$'}plugins = [
            {"name":"Other","status":true,"description":"x","parameters":{}},
            {"name":"Later","status":true,"description":"y","parameters":{}},
            {"name":"KazeBridge","status":true,"description":"z","parameters":{}}
            ];
            """.trimIndent(),
            Charsets.UTF_8,
        )
        File(root, "js/plugins.js.kaze-backup").writeText(
            """var ${'$'}plugins = [{"name":"Other","status":true,"description":"x","parameters":{}}];""",
            Charsets.UTF_8,
        )

        assertTrue(Runtime.removeMvPlugin(root))

        val text = plugins.readText(Charsets.UTF_8)
        assertFalse(text.contains("KazeBridge"))
        assertTrue(text.contains("Later"), "a plugin added after install must survive")
        assertFalse(File(root, "js/plugins.js.kaze-backup").exists())
    }
}
