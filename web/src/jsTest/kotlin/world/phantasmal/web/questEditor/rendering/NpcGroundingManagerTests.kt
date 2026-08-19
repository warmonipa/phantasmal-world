package world.phantasmal.web.questEditor.rendering

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.cell.observeNow
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.web.core.euler
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.PerspectiveCamera
import world.phantasmal.web.externals.three.PlaneGeometry
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.models.NpcPlacementPolicy
import world.phantasmal.web.questEditor.models.SectionModel
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestNpcModel
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.js.unsafeCast

class NpcGroundingManagerTests : WebTestSuite {
    @Test
    fun provider_is_ready_during_registration_and_reacts_to_geometry_changes() = test {
        val policy = NpcPlacementPolicy()
        val npc = createQuestNpcModel(NpcType.Booma, Episode.I, placementPolicy = policy)
        npc.setSection(
            SectionModel(
                id = 1,
                position = Vector3(0.0, 12.0, 0.0),
                rotation = euler(0.0, 0.0, 0.0),
                areaVariant = components.areaStore.getVariant(Episode.I, 0, 0)!!,
            ),
            keepRelativeTransform = true,
        )
        policy.setSpawnOnGround(true)
        val observedHeights = mutableListOf<Double>()
        disposer.add(npc.worldPosition.observeNow { observedHeights += it.y })

        val context = QuestRenderContext(
            document.createElement("canvas").unsafeCast<HTMLCanvasElement>(),
            PerspectiveCamera(),
        )
        disposer.add(NpcGroundingManager(policy, context))
        disposer.add(context)
        assertEquals(12.0, observedHeights.last())

        val ground = Mesh(
            PlaneGeometry(100.0, 100.0).apply { rotateX(-PI / 2.0) },
            MeshBasicMaterial(),
        ).apply {
            position.y = 42.0
            updateMatrixWorld(true)
        }
        context.collisionGeometry = ground

        assertEquals(42.0, observedHeights.last(), absoluteTolerance = 0.0001)
    }
}
