package world.phantasmal.web.test

// WebGLRenderer implementation.
class NopRenderer {
    @Suppress("unused")
    @JsName("capabilities")
    val capabilities = NopCapabilities()

    @Suppress("unused")
    @JsName("render")
    fun render() {
    }

    @Suppress("unused")
    @JsName("setSize")
    fun setSize() {
    }

    @Suppress("unused")
    @JsName("setPixelRatio")
    fun setPixelRatio() {
    }

    @Suppress("unused")
    @JsName("setClearColor")
    fun setClearColor() {
    }

    @Suppress("unused")
    @JsName("clearColor")
    fun clearColor() {
    }

    @Suppress("unused")
    @JsName("dispose")
    fun dispose() {
    }
}

// WebGLCapabilities implementation.
class NopCapabilities {
    // Renderers request half the maximum, so this yields the default anisotropy of 1.
    @Suppress("unused")
    @JsName("getMaxAnisotropy")
    fun getMaxAnisotropy(): Int = 2
}
