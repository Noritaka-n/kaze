package app.kaze.data

import java.io.File







object LibraryScanner {
    private const val MAX_VISITED = 100_000
    private val skipNames = setOf(
        "node_modules",
        "__MACOSX",
        ".git",
        ".svn",
        ".hg",
        "System Volume Information",
        "\$RECYCLE.BIN",
        "Windows",
        "Program Files",
        "Program Files (x86)",
    )

    data class Scan(
        val folders: List<File>,
        val visited: Int,
        val truncated: Boolean,
        val cancelled: Boolean = false,
    )

    fun scan(
        root: File,
        maxDepth: Int = Int.MAX_VALUE,
        limit: Int = 5_000,
        onProgress: (visited: Int, found: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Scan {
        if (!root.isDirectory) return Scan(emptyList(), 0, false)
        if (LibraryStore.detectEngine(root) != null) return Scan(listOf(root), 1, false)

        val found = LinkedHashSet<File>()
        val queue = ArrayDeque<Pair<File, Int>>()
        val seen = HashSet<String>()
        val rootPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        queue += root to 0
        var visited = 0
        var cancelled = false

        while (queue.isNotEmpty() && found.size < limit && visited < MAX_VISITED) {
            if (isCancelled()) {
                cancelled = true
                break
            }
            val (dir, depth) = queue.removeFirst()
            val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!seen.add(canonical)) continue
            if (!canonical.startsWith(rootPath, ignoreCase = true)) continue
            visited++
            if (depth > 0 && LibraryStore.detectEngine(dir) != null) {
                found += dir
                if (visited % 25 == 0) onProgress(visited, found.size)
                continue
            }
            if (depth >= maxDepth) continue
            dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { child ->
                if (!child.isDirectory) return@forEach
                if (child.name.startsWith(".")) return@forEach
                if (child.name in skipNames) return@forEach
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) return@forEach
                queue += child to depth + 1
            }
            if (visited % 25 == 0) onProgress(visited, found.size)
        }
        onProgress(visited, found.size)
        
        
        
        val truncated = !cancelled && (visited >= MAX_VISITED || queue.isNotEmpty())
        return Scan(found.toList(), visited, truncated, cancelled)
    }
}
