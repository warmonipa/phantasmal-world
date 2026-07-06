package world.phantasmal.web.questEditor.controllers

import world.phantasmal.core.externals.browser.FileSystemDirectoryHandle
import world.phantasmal.psolib.Episode
import world.phantasmal.web.test.WebTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals

class FreeRoamControllerTests : WebTestSuite {
    // setupFreeRoamState only stores the handle; it never calls methods on it, so a bare stub is
    // enough to exercise the variant-selection logic.
    private fun stubDirHandle(): FileSystemDirectoryHandle =
        js("({})").unsafeCast<FileSystemDirectoryHandle>()

    @Test
    fun reloading_the_same_area_preserves_the_selected_monster_variant() = testAsync {
        val ctrl = disposer.add(FreeRoamController())
        val dir = stubDirHandle()

        // Episode I forest floors expose v2 (the "Monsters" variant) = [0, 1, 2, 3, 4].
        ctrl.setupFreeRoamState(Episode.I, 1..2, dir, binPrefix = null, isCity = false)
        assertEquals(0, ctrl.freeRoamV2.value, "loads with the first option selected")

        // User picks a non-default variant. onReload is a no-op here, matching handleFreeRoamReload
        // which reloads the quest without touching FreeRoamController's selection state.
        ctrl.setFreeRoamV2(2) { }
        assertEquals(2, ctrl.freeRoamV2.value)

        // Re-loading the SAME area (e.g. after a save, or re-opening the same files) must keep the
        // user's selection instead of snapping back to the first option.
        ctrl.setupFreeRoamState(Episode.I, 1..2, dir, binPrefix = null, isCity = false)
        assertEquals(2, ctrl.freeRoamV2.value, "same-area reload keeps the selected variant")
    }

    @Test
    fun loading_a_different_area_resets_the_selected_monster_variant() = testAsync {
        val ctrl = disposer.add(FreeRoamController())
        val dir = stubDirHandle()

        ctrl.setupFreeRoamState(Episode.I, 1..2, dir, binPrefix = null, isCity = false)
        ctrl.setFreeRoamV2(2) { }
        assertEquals(2, ctrl.freeRoamV2.value)

        // A different area resets to its own first option, even though 2 is also a valid variant
        // there (Episode II CCA/Jungle floors expose v2 = [0, 1, 2]).
        ctrl.setupFreeRoamState(Episode.II, 5..7, dir, binPrefix = null, isCity = false)
        assertEquals(0, ctrl.freeRoamV2.value, "different-area load resets to the default variant")
    }
}
