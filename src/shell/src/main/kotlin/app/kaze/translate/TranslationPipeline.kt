package app.kaze.translate



object TranslationPipeline {
    var status: String = "待命"
        private set

    fun reset() {
        status = "待命"
    }
}
