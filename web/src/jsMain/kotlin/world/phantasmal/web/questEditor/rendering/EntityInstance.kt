package world.phantasmal.web.questEditor.rendering

import world.phantasmal.web.externals.three.InstancedMesh
import world.phantasmal.web.externals.three.Object3D
import world.phantasmal.psolib.fileFormats.quest.ObjectType
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
        if (entity is QuestObjectModel) {
            addDisposable(entity.model.observeChange {
                modelChanged(this.instanceIndex)
            })

            if (entity.type == ObjectType.ForestDoor) {
                entity.properties.value
                    .firstOrNull { it.offset == FOREST_DOOR_PARAM4_OFFSET }
                    ?.let { property ->
                        addDisposable(property.value.observeChange {
                            modelChanged(this.instanceIndex)
                        })
                    }
            }
        }

        // When an NPC's type ID changes, its resolved type (and thus mesh) changes. Trigger the
        // same remove-and-re-add path that object model changes use.
        if (entity is QuestNpcModel) {
            addDisposable(entity.typeId.observeChange {
                modelChanged(this.instanceIndex)
            })
        }

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

    companion object {
        private const val FOREST_DOOR_PARAM4_OFFSET = 52
    }
}
