package app.kaze.data



object ActiveGame {
    var id: String = ""
        private set

    var title: String = ""
        private set

    fun set(gameId: String, gameTitle: String) {
        id = gameId
        title = gameTitle
    }

    fun clear() {
        id = ""
        title = ""
    }
}
