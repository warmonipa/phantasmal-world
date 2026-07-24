package world.phantasmal.web.questEditor.controllers

import world.phantasmal.cell.*
import world.phantasmal.cell.list.ListCell
import world.phantasmal.cell.list.emptyListCell
import world.phantasmal.cell.list.flatMapToList
import world.phantasmal.cell.list.listMap
import world.phantasmal.core.math.degToRad
import world.phantasmal.core.math.radToDeg
import world.phantasmal.psolib.fileFormats.quest.EntityPropType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.displayName
import world.phantasmal.web.core.euler
import world.phantasmal.web.externals.three.Euler
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.commands.*
import world.phantasmal.web.questEditor.models.QuestEntityModel
import world.phantasmal.web.questEditor.models.QuestEntityPropModel
import world.phantasmal.web.questEditor.models.QuestNpcModel
import world.phantasmal.web.questEditor.models.QuestObjectModel
import world.phantasmal.web.questEditor.stores.AreaStore
import world.phantasmal.web.questEditor.stores.AsmStore
import world.phantasmal.web.questEditor.stores.QuestEditorStore
import world.phantasmal.web.questEditor.stores.QuestEditorUiStore
import world.phantasmal.webui.controllers.Controller

sealed class EntityInfoPropModel(
    protected val store: QuestEditorStore,
    protected val prop: QuestEntityPropModel,
) {
    val label = prop.name + ":"
    val isScriptLabel: Boolean = prop.name == "Script label"

    protected fun setPropValue(prop: QuestEntityPropModel, value: Any) {
        store.selectedEntity.value?.let { entity ->
            store.executeAction(
                EditEntityPropCommand(
                    store,
                    entity,
                    prop,
                    value,
                    prop.value.value,
                )
            )
        }
    }

    class I32(store: QuestEditorStore, prop: QuestEntityPropModel) :
        EntityInfoPropModel(store, prop) {

        /** Non-null when this property should render as a color select box. */
        val colorOptions: List<ColorOption>? =
            if (prop.name == "Color" && isFenceColorProp(store)) COLOR_OPTIONS else null

        @Suppress("UNCHECKED_CAST")
        val value: Cell<Int> = if (isForestDoor(store)) {
            when (prop.name) {
                "Door ID" -> (prop.value as Cell<Int>).map { packedValue ->
                    if (packedValue == -1) packedValue else packedValue and 0xFF
                }
                "Door Display Number" -> (prop.value as Cell<Int>).map { packedValue ->
                    (packedValue ushr 8) and 0xFF
                }
                else -> prop.value as Cell<Int>
            }
        } else {
            prop.value as Cell<Int>
        }

        val selectedColor: Cell<ColorOption?> =
            if (colorOptions != null) {
                value.map { v -> colorOptions.find { it.value == v } }
            } else {
                cell(null)
            }

        val showGoToEvent: Boolean = prop.name == "Event ID"

        val canGoToEvent: Cell<Boolean> = store.canGoToEvent(value)

        fun setValue(value: Int) {
            val actualValue = if (isForestDoor(store)) {
                @Suppress("UNCHECKED_CAST")
                val originalValue = (prop.value as Cell<Int>).value
                when (prop.name) {
                    "Door ID" -> {
                        if (value == -1) value
                        else (originalValue and 0xFF.inv()) or (value and 0xFF)
                    }
                    "Door Display Number" -> {
                        (originalValue and (0xFF shl 8).inv()) or ((value and 0xFF) shl 8)
                    }
                    else -> value
                }
            } else {
                value
            }
            setPropValue(prop, actualValue)
        }

        fun goToEvent() {
            store.goToEvent(value.value)
        }

        private fun isForestDoor(store: QuestEditorStore): Boolean {
            val entity = store.selectedEntity.value
            return entity is QuestObjectModel && entity.type == ObjectType.ForestDoor
        }
    }

    class F32(store: QuestEditorStore, prop: QuestEntityPropModel) :
        EntityInfoPropModel(store, prop) {

        /** Non-null when this property should render as a color select box. */
        val colorOptions: List<ColorOption>? =
            if (prop.name == "Color" && isFenceColorProp(store)) COLOR_OPTIONS else null

        val value: Cell<Double> = prop.value.map { (it as Float).toDouble() }

        val selectedColor: Cell<ColorOption?> =
            if (colorOptions != null) {
                value.map { v -> colorOptions.find { it.value == v.toInt() } }
            } else {
                cell(null)
            }

        fun setValue(value: Double) {
            setPropValue(prop, value.toFloat())
        }
    }

    class Angle(store: QuestEditorStore, prop: QuestEntityPropModel) :
        EntityInfoPropModel(store, prop) {

        val value: Cell<Double> = prop.value.map { radToDeg((it as Float).toDouble()) }

        fun setValue(value: Double) {
            setPropValue(prop, degToRad(value).toFloat())
        }
    }

    data class ColorOption(val value: Int, val name: String) {
        override fun toString(): String = name
    }

    companion object {
        val COLOR_OPTIONS = listOf(
            ColorOption(0, "Orange"),
            ColorOption(1, "Blue"),
            ColorOption(2, "Green"),
            ColorOption(3, "Purple"),
        )

        private val FENCE_COLOR_TYPES: Set<ObjectType> = setOf(
            ObjectType.LaserFence,
            ObjectType.LaserSquareFence,
            ObjectType.LaserFenceEx,
            ObjectType.LaserSquareFenceEx,
            ObjectType.ForestSwitch,
            ObjectType.ForestLaserFenceSwitch,
            ObjectType.RuinsFenceSwitch,
            ObjectType.RuinsLaserFence4x2,
            ObjectType.RuinsLaserFence6x2,
            ObjectType.RuinsLaserFence4x4,
            ObjectType.RuinsLaserFence6x4,
        )

        private fun isFenceColorProp(store: QuestEditorStore): Boolean {
            val entity = store.selectedEntity.value
            return entity is QuestObjectModel && entity.type in FENCE_COLOR_TYPES
        }
    }
}

class EntityInfoController(
    private val areaStore: AreaStore,
    private val questEditorStore: QuestEditorStore,
    private val questEditorUiStore: QuestEditorUiStore,
    private val asmStore: AsmStore,
    private val onActivateAsmEditor: () -> Unit = {},
) : Controller() {
    val unavailable: Cell<Boolean> = questEditorStore.selectedEntity.isNull()
    val enabled: Cell<Boolean> = questEditorStore.questEditingEnabled

    /**
     * Raw type ID at offset 0 of the entity data. Editable for NPCs; editing it changes the
     * resolved [world.phantasmal.psolib.fileFormats.quest.NpcType].
     */
    val typeId: Cell<Int> = questEditorStore.selectedEntity.flatMap { entity ->
        when (entity) {
            is QuestNpcModel -> entity.typeId
            is QuestObjectModel -> cell(entity.entity.typeId.toInt())
            else -> zeroIntCell()
        }
    }

    /** Only NPC type IDs are editable; object type IDs stay read-only. */
    val typeIdEnabled: Cell<Boolean> =
        map(enabled, questEditorStore.selectedEntity) { en, entity -> en && entity is QuestNpcModel }

    val type: Cell<String> = questEditorStore.selectedEntity.flatMap { entity ->
        when (entity) {
            // Re-resolve the kind when the raw type ID or effective map changes.
            is QuestNpcModel -> entity.resolvedTypeRevision.map {
                if (entity.type.enemy) "Enemy" else "NPC"
            }
            null -> cell("")
            else -> cell("Object")
        }
    }

    val name: Cell<String> = questEditorStore.selectedEntity.flatMap { entity ->
        when (entity) {
            // Re-resolve the name when the raw type ID or effective map changes, or the Ultimate
            // toggle flips (NPCs like Sinow Beat → Sinow Blue are renamed on Ultimate).
            is QuestNpcModel ->
                map(entity.resolvedTypeRevision, questEditorUiStore.ultimate) { _, ult ->
                    entity.type.displayName(ult)
                }
            null -> cell("")
            else -> questEditorUiStore.ultimate.map { ult -> entity.type.displayName(ult) }
        }
    }

    val appearFlag: Cell<String> = questEditorStore.selectedEntity
        .map { entity ->
            if (entity is QuestObjectModel) entity.entity.groupId.toString() else ""
        }

    val appearFlagHidden: Cell<Boolean> = questEditorStore.selectedEntity.map { it !is QuestObjectModel }

    val sectionId: Cell<Int> = questEditorStore.selectedEntity
        .flatMap { it?.sectionId ?: zeroIntCell() }

    val waveId: Cell<Int> = questEditorStore.selectedEntity
        .flatMap { entity ->
            if (entity is QuestNpcModel) {
                entity.wave.map { it.id }
            } else {
                zeroIntCell()
            }
        }

    val waveHidden: Cell<Boolean> = questEditorStore.selectedEntity.map { it !is QuestNpcModel }

    private val pos: Cell<Vector3> =
        questEditorStore.selectedEntity.flatMap { it?.position ?: DEFAULT_POSITION }
    val posX: Cell<Double> = pos.map { it.x }
    val posY: Cell<Double> = pos.map { it.y }
    val posZ: Cell<Double> = pos.map { it.z }

    private val worldPos: Cell<Vector3> =
        questEditorStore.selectedEntity.flatMap { it?.worldPosition ?: DEFAULT_POSITION }
    val worldPosX: Cell<Double> = worldPos.map { it.x }
    val worldPosY: Cell<Double> = worldPos.map { it.y }
    val worldPosZ: Cell<Double> = worldPos.map { it.z }

    private val rot: Cell<Euler> =
        questEditorStore.selectedEntity.flatMap { it?.rotation ?: DEFAULT_ROTATION }
    val rotX: Cell<Double> = rot.map { radToDeg(it.x) }
    val rotY: Cell<Double> = rot.map { radToDeg(it.y) }
    val rotZ: Cell<Double> = rot.map { radToDeg(it.z) }

    val props: ListCell<EntityInfoPropModel> =
        questEditorStore.selectedEntity.flatMapToList { entity ->
            entity?.properties?.listMap(::toInfoPropModel) ?: emptyListCell()
        }

    private fun toInfoPropModel(prop: QuestEntityPropModel): EntityInfoPropModel =
        when (prop.type) {
            EntityPropType.I32 -> EntityInfoPropModel.I32(questEditorStore, prop)
            EntityPropType.U16 -> EntityInfoPropModel.I32(questEditorStore, prop)
            EntityPropType.F32 -> EntityInfoPropModel.F32(questEditorStore, prop)
            EntityPropType.Angle -> EntityInfoPropModel.Angle(questEditorStore, prop)
        }

    fun focused() {
        questEditorStore.makeMainUndoCurrent()
    }

    fun goToScriptLabel(labelId: Int) {
        onActivateAsmEditor()
        asmStore.goToLabel(labelId)
    }

    suspend fun setSectionId(sectionId: Int) {
        questEditorStore.currentQuest.value?.let { quest ->
            questEditorStore.selectedEntity.value?.let { entity ->
                val variant = quest.floorToVariantMap[entity.floorId]
                    ?: quest.areaVariants.value.firstOrNull { it.area.id == entity.floorId }
                    ?: return
                val section = areaStore.getSection(
                    variant.episode,
                    variant,
                    sectionId,
                )
                questEditorStore.executeAction(
                    EditEntitySectionCommand(
                        questEditorStore,
                        entity,
                        sectionId,
                        section,
                        entity.sectionId.value,
                        entity.section.value,
                    )
                )
            }
        }
    }

    fun setWaveId(waveId: Int) {
        (questEditorStore.selectedEntity.value as? QuestNpcModel)?.let { npc ->
            questEditorStore.executeAction(
                EditEntityPropertyCommand(
                    questEditorStore,
                    "Edit ${npc.type.simpleName} wave",
                    npc,
                    QuestNpcModel::setWaveId,
                    waveId,
                    npc.wave.value.id,
                )
            )
        }
    }

    fun setTypeId(typeId: Int) {
        (questEditorStore.selectedEntity.value as? QuestNpcModel)?.let { npc ->
            questEditorStore.executeAction(
                EditEntityPropertyCommand(
                    questEditorStore,
                    "Edit ${npc.type.simpleName} type ID",
                    npc,
                    QuestNpcModel::setTypeId,
                    typeId,
                    npc.typeId.value,
                )
            )
        }
    }

    fun setPosX(x: Double) {
        questEditorStore.selectedEntity.value?.let { entity ->
            val pos = entity.position.value
            setPos(entity, x, pos.y, pos.z)
        }
    }

    fun setPosY(y: Double) {
        questEditorStore.selectedEntity.value?.let { entity ->
            val pos = entity.position.value
            setPos(entity, pos.x, y, pos.z)
        }
    }

    fun setPosZ(z: Double) {
        questEditorStore.selectedEntity.value?.let { entity ->
            val pos = entity.position.value
            setPos(entity, pos.x, pos.y, z)
        }
    }

    private fun setPos(entity: QuestEntityModel<*, *>, x: Double, y: Double, z: Double) {
        if (!enabled.value) return

        questEditorStore.executeAction(
            TranslateEntityCommand(
                questEditorStore,
                entity,
                newSection = null,
                oldSection = null,
                newPosition = Vector3(x, y, z),
                oldPosition = entity.position.value,
                world = false,
            )
        )
    }

    fun setWorldPosX(x: Double) {
        questEditorStore.selectedEntity.value?.let { entity ->
            val pos = entity.worldPosition.value
            setWorldPos(entity, x, pos.y, pos.z)
        }
    }

    fun setWorldPosY(y: Double) {
        questEditorStore.selectedEntity.value?.let { entity ->
            val pos = entity.worldPosition.value
            setWorldPos(entity, pos.x, y, pos.z)
        }
    }

    fun setWorldPosZ(z: Double) {
        questEditorStore.selectedEntity.value?.let { entity ->
            val pos = entity.worldPosition.value
            setWorldPos(entity, pos.x, pos.y, z)
        }
    }

    private fun setWorldPos(entity: QuestEntityModel<*, *>, x: Double, y: Double, z: Double) {
        if (!enabled.value) return

        questEditorStore.executeAction(
            TranslateEntityCommand(
                questEditorStore,
                entity,
                newSection = null,
                oldSection = null,
                newPosition = Vector3(x, y, z),
                oldPosition = entity.worldPosition.value,
                world = true,
            )
        )
    }

    fun setRotX(x: Double) {
        questEditorStore.selectedEntity.value?.let { entity ->
            val rot = entity.rotation.value
            setRot(entity, degToRad(x), rot.y, rot.z)
        }
    }

    fun setRotY(y: Double) {
        questEditorStore.selectedEntity.value?.let { entity ->
            val rot = entity.rotation.value
            setRot(entity, rot.x, degToRad(y), rot.z)
        }
    }

    fun setRotZ(z: Double) {
        questEditorStore.selectedEntity.value?.let { entity ->
            val rot = entity.rotation.value
            setRot(entity, rot.x, rot.y, degToRad(z))
        }
    }

    private fun setRot(entity: QuestEntityModel<*, *>, x: Double, y: Double, z: Double) {
        if (!enabled.value) return

        questEditorStore.executeAction(
            RotateEntityCommand(
                questEditorStore,
                entity,
                euler(x, y, z),
                entity.rotation.value,
                world = false,
            )
        )
    }

    companion object {
        private val DEFAULT_POSITION = cell(Vector3(0.0, 0.0, 0.0))
        private val DEFAULT_ROTATION = cell(euler(0.0, 0.0, 0.0))
    }
}
