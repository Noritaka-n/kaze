package app.kaze.data






object ScanState {
    var running = false
        private set
    var visited = 0
        private set
    var found = 0
        private set
    var message = ""
        private set
    var cancelRequested = false
        private set

    fun start() {
        running = true
        visited = 0
        found = 0
        cancelRequested = false
        message = "扫描中…"
    }

    fun update(visited: Int, found: Int) {
        this.visited = visited
        this.found = found
        message = "已访问 $visited 个目录 · 找到 $found 个游戏"
    }

    fun cancel() {
        cancelRequested = true
        message = "正在取消…"
    }

    fun finish(notice: String) {
        running = false
        message = notice
    }
}
