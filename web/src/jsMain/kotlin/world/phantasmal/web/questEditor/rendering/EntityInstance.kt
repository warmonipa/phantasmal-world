package world.phantasmal.web.questEditor.rendering

import world.phantasmal.core.disposable.Disposable
import world.phantasmal.web.externals.three.InstancedMesh
import world.phantasmal.web.externals.three.Object3D
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.questEditor.models.QuestEntityModel
import world.phantasmal.web.questEditor.models.QuestNpcModel
import world.phantasmal.web.questEditor.models.QuestObjectModel

private const val FOREST_DOOR_PARAM4_OFFSET = 52

class EntityInstance(
    entity: QuestEntityModel<*, *>,
    mesh: InstancedMesh,
    instanceIndex: Int,
    modelChanged: (instanceIndex: Int) -> Unit,
) : Instance<QuestEntityModel<*, *>>(entity, mesh, instanceIndex) {
    init {
        addDisposables(
            *observeEntityRenderKey(entity) { modelChanged(this.instanceIndex) }.toTypedArray(),
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

/**
 * Observes the entity state that determines which [EntityInstanceContainer] renders [entity].
 */
internal fun observeEntityRenderKey(
    entity: QuestEntityModel<*, *>,
    changed: () -> Unit,
): List<Disposable> = buildList {
    if (entity is QuestObjectModel) {
        add(entity.model.observeChange { changed() })

        if (entity.type == ObjectType.ForestDoor) {
            entity.properties.value
                .firstOrNull { it.offset == FOREST_DOOR_PARAM4_OFFSET }
                ?.let { property -> add(property.value.observeChange { changed() }) }
        }
    }

    // A raw type-ID edit or effective-map change can alter the resolved NPC type.
    if (entity is QuestNpcModel) {
        add(entity.resolvedTypeRevision.observeChange { changed() })
    }
}
