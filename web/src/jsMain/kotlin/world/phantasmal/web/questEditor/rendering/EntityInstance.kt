package world.phantasmal.web.questEditor.rendering

import world.phantasmal.core.disposable.Disposable
import world.phantasmal.web.externals.three.InstancedMesh
import world.phantasmal.web.externals.three.Object3D
import world.phantasmal.web.questEditor.loading.entityTextureVariant
import world.phantasmal.web.questEditor.models.QuestEntityModel
import world.phantasmal.web.questEditor.models.QuestNpcModel
import world.phantasmal.web.questEditor.models.QuestObjectModel

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

        entityTextureVariant(entity.type)?.let { variant ->
            entity.properties.value
                .firstOrNull { it.offset == variant.paramOffset }
                ?.let { property -> add(property.value.observeChange { changed() }) }
        }
    }

    // A raw type-ID edit or effective-map change can alter the resolved NPC type.
    if (entity is QuestNpcModel) {
        add(entity.resolvedTypeRevision.observeChange { changed() })
    }
}
