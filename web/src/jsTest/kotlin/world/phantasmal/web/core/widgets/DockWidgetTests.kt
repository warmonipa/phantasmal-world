package world.phantasmal.web.core.widgets

import world.phantasmal.web.externals.goldenLayout.GoldenLayout
import world.phantasmal.webui.obj
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertFalse

class DockWidgetTests {
    @Test
    fun disables_legacy_window_lifecycle_binding() {
        var legacyBindingCalled = false
        val goldenLayout = obj<dynamic> {
            _bindEvents = { legacyBindingCalled = true }
        }.unsafeCast<GoldenLayout>()

        disableLegacyWindowLifecycleBinding(goldenLayout)
        goldenLayout.asDynamic()._bindEvents()

        assertFalse(legacyBindingCalled)
    }
}
