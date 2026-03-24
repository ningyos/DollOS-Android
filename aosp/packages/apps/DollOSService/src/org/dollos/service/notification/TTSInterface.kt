package org.dollos.service.notification

interface TTSInterface {
    fun speak(text: String, priority: Int)
    fun stop()
    val isAvailable: Boolean
}

class NoOpTTS : TTSInterface {
    override fun speak(text: String, priority: Int) {}
    override fun stop() {}
    override val isAvailable: Boolean = false
}
