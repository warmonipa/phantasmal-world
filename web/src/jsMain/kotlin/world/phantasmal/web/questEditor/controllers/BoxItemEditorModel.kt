package world.phantasmal.web.questEditor.controllers

import world.phantasmal.cell.*
import world.phantasmal.psolib.fileFormats.quest.BoxItemDefinition
import world.phantasmal.psolib.fileFormats.quest.BoxItemField
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.core.commands.Command
import world.phantasmal.web.questEditor.models.BOX_ITEM_NAMES
import world.phantasmal.web.questEditor.models.QuestObjectModel
import world.phantasmal.web.questEditor.stores.QuestEditorStore

enum class BoxItemCategory(val label: String, val code: Int) {
    Weapon("Weapon", 0x000100), Armor("Armor", 0x010100), Shield("Shield", 0x010200),
    Unit("Unit", 0x010300), Mag("Mag", 0x020000), Tool("Tool / technique", 0x030000),
    Meseta("Meseta", 0x040000);
    override fun toString() = label
}

data class BoxItemChoice(val code: Int, val name: String) {
    override fun toString() = "$name [${code.toString(16).uppercase().padStart(6, '0')}]"
}

/** A semantic view over one object's existing properties; all edits remain one undo action. */
class BoxItemEditorModel(private val store: QuestEditorStore, val entity: QuestObjectModel) {
    private val props = entity.properties.value.associateBy { it.offset }
    private val first = props.getValue(52)
    private val second = props.getValue(56)
    private val third = props.getValue(60)
    private val exact = props.getValue(48)
    private val random = props.getValue(40)
    private val desert = entity.type == ObjectType.DesertFixedTypeBoxBreakableCrystals

    val definition: Cell<BoxItemDefinition> = map(first.value, second.value, third.value) { a, b, c ->
        BoxItemDefinition(a as Int, b as Int, c as Int)
    }
    val active: Cell<Boolean> = random.value.map { if (desert) isDesertItemMode(it as Float) else !((it as Float) > 0f) }
    val isExact: Cell<Boolean> = exact.value.map { it != 0f }
    val category: Cell<BoxItemCategory?> = definition.map { def ->
        BoxItemCategory.values().find {
            it.code ushr 16 == def.itemClass &&
                (def.itemClass != 1 || ((it.code ushr 8) and 255) == def.itemKind)
        }
    }
    val choices: Cell<List<BoxItemChoice>> = category.map { cat ->
        if (cat == null) emptyList() else BOX_ITEM_NAMES.entries.filter { (code, _) ->
            code ushr 16 == cat.code ushr 16 &&
                (code ushr 16 != 1 || (code and 0xFFFF00) == (cat.code and 0xFFFF00))
        }.map { BoxItemChoice(it.key, it.value) }.sortedBy { it.name.lowercase() }
    }
    val selectedItem: Cell<BoxItemChoice?> = map(definition, choices) { def, items ->
        items.find { it.code == def.itemCode }
    }
    val summary: Cell<String> = map(definition, isExact, active, category) { def, fixed, enabled, cat ->
        when {
            !enabled -> "Item definition is inactive in the current contents mode."
            !fixed -> if (cat == BoxItemCategory.Mag) "Default Mag; stats are initialized by the game."
                else "Random ${cat?.label ?: "unknown category"}; generated from the area's drop table."
            else -> {
                val name = BOX_ITEM_NAMES[def.itemCode] ?: "Unknown item [${def.itemCode.toString(16).uppercase().padStart(6, '0')}]"
                when (def.itemClass) {
                    0 -> "$name +${def.read(BoxItemField.Grind)}"
                    3 -> if (def.isTechnique) "$name Lv.${def.read(BoxItemField.TechniqueLevel)}" else "$name ×1"
                    4 -> "${def.read(BoxItemField.Meseta)} Meseta"
                    else -> name
                }
            }
        }
    }
    val fields: Cell<List<BoxItemField>> = map(definition, isExact) { def, fixed ->
        if (fixed) def.fields else emptyList()
    }
    val note: Cell<String> = definition.map { def -> when (def.itemClass) {
        0 -> if (def.itemKind in 0x70..0x88) "S-rank custom names cannot be edited here."
            else ""
        1 -> if (def.itemKind == 3) "Box directives cannot encode unit +/- modifiers."
            else "Box directives cannot encode DEF/EVP bonuses."
        2 -> "Only the Mag type can be specified. Stats and color use the game's defaults."
        3 -> "Exact tools drop one item. A stack quantity cannot be specified."
        else -> ""
    } }

    fun setCategory(category: BoxItemCategory) {
        if (category != this.category.value) setDefinition(definition.value.withItemCode(category.code))
    }
    fun setItem(item: BoxItemChoice) {
        if (item.code != definition.value.itemCode) setDefinition(definition.value.withItemCode(item.code))
    }
    fun setField(field: BoxItemField, value: Int): Boolean {
        val def = definition.value
        if (value !in field.min..field.max || value % field.step != 0 ||
            (field == BoxItemField.TechniqueLevel && value > def.maxTechniqueLevel)) return false
        setDefinition(def.withField(field, value))
        return true
    }

    private fun setDefinition(value: BoxItemDefinition) {
        val old = definition.value
        if (!store.questEditingEnabled.value || !entity.isEditable || old == value) return
        store.executeAction(object : Command {
            override val description = "Edit box item"
            override fun execute() = apply(value)
            override fun undo() = apply(old)
            private fun apply(def: BoxItemDefinition) = mutate {
                store.setEntityProp(entity, first, def.data1)
                store.setEntityProp(entity, second, def.data2)
                store.setEntityProp(entity, third, def.data3)
            }
        })
    }

    companion object {
        // BB DerolleEnemyHead_UpdatePhase_00677770 compares param1 against 2.0 and 3.0.
        fun isDesertItemMode(contents: Float): Boolean = contents >= 2f && contents < 3f

        fun supports(type: ObjectType): Boolean = type in setOf(ObjectType.FixedTypeBox,
            ObjectType.FixedBoxTypeRuins, ObjectType.SpecialBoxCca,
            ObjectType.DesertFixedTypeBoxBreakableCrystals)
    }
}
