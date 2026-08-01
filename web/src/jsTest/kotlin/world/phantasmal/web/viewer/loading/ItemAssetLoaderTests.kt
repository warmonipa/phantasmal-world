package world.phantasmal.web.viewer.loading

import world.phantasmal.psolib.fileFormats.Vec3
import world.phantasmal.psolib.fileFormats.ninja.NinjaEvaluationFlags
import world.phantasmal.psolib.fileFormats.ninja.XjObject
import world.phantasmal.web.test.WebTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ItemAssetLoaderTests : WebTestSuite {
    @Test
    fun multi_root_item_models_keep_every_component() = test {
        val wok = xjObject()
        val ladle = xjObject()

        val combined = combineXjRoots(listOf(wok, ladle))

        assertEquals(2, combined.children.size)
        assertSame(wok, combined.children[0])
        assertSame(ladle, combined.children[1])
    }

    @Test
    fun single_root_item_models_are_not_wrapped() = test {
        val root = xjObject()

        assertSame(root, combineXjRoots(listOf(root)))
    }

    @Test
    fun item_models_must_have_a_root() = test {
        assertFailsWith<IllegalArgumentException> { combineXjRoots(emptyList()) }
    }

    private fun xjObject() = XjObject(
        offset = 0,
        evaluationFlags = NinjaEvaluationFlags(0),
        model = null,
        position = ZERO,
        rotation = ZERO,
        scale = ONE,
        children = mutableListOf(),
    )

    private companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val ONE = Vec3(1f, 1f, 1f)
    }
}
