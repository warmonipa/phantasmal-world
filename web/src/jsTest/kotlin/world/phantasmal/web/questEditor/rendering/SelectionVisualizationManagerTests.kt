package world.phantasmal.web.questEditor.rendering

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.externals.three.PerspectiveCamera
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.test.createQuestObjectModel
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionVisualizationManagerTests : WebTestSuite {
    @Test
    fun visualizations_follow_selection_and_rendered_object_visibility() = testAsync {
        val obj = createQuestObjectModel(ObjectType.ObjRoomID).apply {
            entity.data.setFloat(40, 2.0f)
            setSectionInitialized()
        }
        components.questEditorStore.setCurrentQuest(createQuestModel(objects = listOf(obj)))
        val context = QuestRenderContext(
            document.createElement("canvas").unsafeCast<HTMLCanvasElement>(),
            PerspectiveCamera(),
        )
        val manager = disposer.add(
            SelectionVisualizationManager(
                components.questEditorStore,
                context,
                SectionIdRenderer(),
            )
        )
        disposer.add(context)

        components.questEditorStore.setSelectedEntity(obj)
        assertEquals(0, context.helpers.children.size)

        manager.setVisibleObjects(listOf(obj))
        assertEquals(1, context.helpers.children.size)

        manager.setVisibleObjects(emptyList())
        assertEquals(0, context.helpers.children.size)

        manager.setVisibleObjects(listOf(obj))
        assertEquals(1, context.helpers.children.size)
    }
}
