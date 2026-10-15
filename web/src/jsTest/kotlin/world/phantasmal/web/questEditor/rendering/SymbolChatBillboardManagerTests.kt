package world.phantasmal.web.questEditor.rendering

import kotlinx.browser.document
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.PerspectiveCamera
import world.phantasmal.web.questEditor.loading.SymbolChatColliRepository
import world.phantasmal.web.questEditor.widgets.SymbolChatRenderer
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestObjectModel
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class SymbolChatBillboardManagerTests : WebTestSuite {
    @Test
    fun billboard_gets_a_new_texture_when_its_size_changes() = testAsync {
        val repository = disposer.add(SymbolChatColliRepository(components.assetLoader))
        withTimeout(5_000) {
            while (!repository.available.value) yield()
        }
        val context = disposer.add(
            QuestRenderContext(
                document.createElement("canvas").unsafeCast<HTMLCanvasElement>(),
                PerspectiveCamera(),
            )
        )
        disposer.add(
            SymbolChatBillboardManager(components.questEditorStore, repository, context)
        )
        val entity = createQuestObjectModel(ObjectType.SymbolChatObject)
        val props = entity.properties.value
        props.first { it.name == "SC ID 1" }.setValue(0)
        props.first { it.name == "SC ID 2" }.setValue(0xFFFF) // Out of range: no stage.
        props.first { it.name == "SC ID 3" }.setValue(0xFFFF)

        components.questEditorStore.setSelectedEntity(entity)
        val mesh = context.helpers.children.first { it.name == "SymbolChatBillboard" }
            .unsafeCast<Mesh>()
        val oneStageMap = mesh.asDynamic().material.map

        // A second stage widens the canvas. three.js r135+ can't resize an uploaded texture, so
        // the billboard needs a new one.
        props.first { it.name == "SC ID 2" }.setValue(1)
        val twoStageMap = mesh.asDynamic().material.map

        assertEquals(2 * SymbolChatRenderer.CANVAS_WIDTH, twoStageMap.image.width.unsafeCast<Int>())
        assertNotSame(oneStageMap, twoStageMap)
    }
}
