package world.phantasmal.web.questEditor.rendering

import world.phantasmal.cell.Cell
import world.phantasmal.cell.observeNow
import world.phantasmal.web.externals.three.BufferGeometry
import world.phantasmal.web.externals.three.BufferGeometryUtils
import world.phantasmal.web.externals.three.Color
import world.phantasmal.web.externals.three.ConeGeometry
import world.phantasmal.web.externals.three.CylinderGeometry
import world.phantasmal.web.externals.three.InstancedMesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.Object3D
import world.phantasmal.web.questEditor.models.QuestEntityModel
import world.phantasmal.webui.obj
import kotlin.math.PI

/**
 * Renders lightweight arrows in front of quest objects and NPCs. PSO entities face local -Z.
 */
internal class EntityDirectionIndicatorContainer(showDirections: Cell<Boolean>) :
    InstanceContainer<QuestEntityModel<*, *>, EntityDirectionIndicatorInstance>(
        InstancedMesh(
            createEntityDirectionArrowGeometry(),
            MeshBasicMaterial(obj {
                color = Color(COLOR)
                transparent = true
                opacity = 0.9
            }),
            MAX_INSTANCES,
        ).apply {
            count = 0
            frustumCulled = false
            renderOrder = 9998
        }
    ) {
    private val visibilitySubscription = showDirections.observeNow { mesh.visible = it }

    override fun dispose() {
        visibilitySubscription.dispose()
        clearInstances()
        super.dispose()
    }

    override fun createInstance(
        entity: QuestEntityModel<*, *>,
        index: Int,
    ): EntityDirectionIndicatorInstance =
        EntityDirectionIndicatorInstance(entity, mesh, index)

    companion object {
        private const val MAX_INSTANCES = 1000
        private const val COLOR = 0x29D8FF
    }
}

internal class EntityDirectionIndicatorInstance(
    entity: QuestEntityModel<*, *>,
    mesh: InstancedMesh,
    instanceIndex: Int,
) : Instance<QuestEntityModel<*, *>>(entity, mesh, instanceIndex) {
    init {
        addDisposables(
            entity.worldPosition.observeChange { updateMatrix() },
            entity.worldRotation.observeChange { updateMatrix() },
        )
    }

    override fun updateObjectMatrix(obj: Object3D) {
        obj.position.copy(entity.worldPosition.value)
        obj.rotation.copy(entity.worldRotation.value)
        obj.updateMatrix()
    }
}

/** Creates a shaft and arrowhead extending from the entity origin along local -Z. */
internal fun createEntityDirectionArrowGeometry(): BufferGeometry {
    val shaftLength = 22.0
    val headLength = 8.0
    val height = 4.0

    val shaft = CylinderGeometry(0.8, 0.8, shaftLength, 6).apply {
        rotateX(-PI / 2)
        translate(0.0, height, -shaftLength / 2)
    }
    val head = ConeGeometry(3.5, headLength, 8).apply {
        rotateX(-PI / 2)
        translate(0.0, height, -shaftLength - headLength / 2)
    }

    return BufferGeometryUtils.mergeBufferGeometries(arrayOf(shaft, head), false)!!.apply {
        computeBoundingBox()
        computeBoundingSphere()
    }
}
