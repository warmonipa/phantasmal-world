package world.phantasmal.psolib.fileFormats.quest

/**
 * A map object's compact drop directive, not serialized inventory ItemData.
 * GC client: TItemData::unpack_from_item @ 0x8010C744; BB: newserv ItemCreator.
 * Keep the original words so editing one field preserves every other bit.
 */
data class BoxItemDefinition(val data1: Int, val data2: Int, val data3: Int) {
    val itemClass: Int get() = (data1 ushr 24) and 15
    val itemKind: Int get() = (((data1 ushr 16) and 255) + if (itemClass <= 1) 1 else 0) and 255
    val isTechnique: Boolean get() = itemClass == 3 && itemKind == 2
    val itemCode: Int get() = when (itemClass) {
        2 -> 0x020000 or (itemKind shl 8) // serialize_mag overwrites the subtype byte with level
        4 -> 0x040000
        else -> (itemClass shl 16) or (itemKind shl 8) or
            (if (isTechnique) data1 and 255 else (data1 ushr 8) and 255)
    }

    fun withItemCode(code: Int): BoxItemDefinition {
        require(code in 0..0x04FFFF)
        val category = code ushr 16
        val kind = (code ushr 8) and 255
        val subtype = code and 255
        val encodedKind = (kind - if (category <= 1) 1 else 0) and 255
        // A category change discards attributes that have different meanings in the new category.
        val base = if (category == itemClass && kind == itemKind) this else BoxItemDefinition(0, 0, 0)
        val head = (base.data1 and 0xF0000000.toInt()) or (category shl 24) or (encodedKind shl 16)
        return if (category == 3 && kind == 2) {
            val level = if (base.itemCode == code) base.data1 and 0xFF00 else 0
            base.copy(data1 = head or level or subtype)
        } else {
            base.copy(data1 = head or (subtype shl 8) or (base.data1 and 255))
        }
    }

    fun read(field: BoxItemField): Int {
        val word = when (field.word) { 1 -> data1; 2 -> data2; else -> data3 }
        val bits = (word ushr field.shift) and field.mask
        return when {
            field == BoxItemField.TechniqueLevel -> bits + 1
            field == BoxItemField.Meseta -> bits * 10
            field.signed -> bits.toByte().toInt()
            else -> bits
        }
    }

    fun withField(field: BoxItemField, value: Int): BoxItemDefinition {
        require(value in field.min..field.max && value % field.step == 0)
        if (field == BoxItemField.TechniqueLevel) require(value <= maxTechniqueLevel)
        val bits = when (field) {
            BoxItemField.TechniqueLevel -> value - 1
            BoxItemField.Meseta -> value / 10
            else -> value
        }
        fun change(word: Int) = (word and (field.mask shl field.shift).inv()) or
            ((bits and field.mask) shl field.shift)
        return when (field.word) {
            1 -> copy(data1 = change(data1))
            2 -> copy(data2 = change(data2))
            else -> copy(data3 = change(data3))
        }
    }

    /** Fields this directive can actually encode. Units cannot encode their +/- modifier. */
    val fields: List<BoxItemField> get() = when (itemClass) {
        0 -> listOf(BoxItemField.Grind, BoxItemField.Special, BoxItemField.Untekked,
            BoxItemField.Wrapped, BoxItemField.Attribute1, BoxItemField.Percent1,
            BoxItemField.Attribute2, BoxItemField.Percent2, BoxItemField.Attribute3, BoxItemField.Percent3)
            .let { if (itemKind in 0x70..0x88) it.take(4) else it }
        1 -> if (itemKind == 1) listOf(BoxItemField.Slots) else emptyList()
        3 -> if (isTechnique) listOf(BoxItemField.TechniqueLevel) else emptyList()
        4 -> listOf(BoxItemField.Meseta)
        else -> emptyList()
    }

    val maxTechniqueLevel: Int get() = when (itemCode and 255) {
        14, 17 -> 1 // Ryuker and Reverser
        16 -> 7 // Anti
        else -> 30
    }
}

enum class BoxItemField(
    val label: String, val word: Int, val shift: Int, val mask: Int,
    val min: Int = 0, val max: Int = mask, val step: Int = 1, val signed: Boolean = false,
) {
    Grind("Grind", 2, 24, 255),
    Special("Special ID", 1, 0, 63),
    Untekked("Untekked (0/1)", 1, 7, 1),
    Wrapped("Wrapped (0/1)", 1, 6, 1),
    Attribute1("Attribute 1", 2, 8, 255, max = 5),
    Percent1("Attribute 1 %", 2, 0, 255, -100, 100, signed = true),
    Attribute2("Attribute 2", 3, 24, 255, max = 5),
    Percent2("Attribute 2 %", 3, 16, 255, -100, 100, signed = true),
    Attribute3("Attribute 3", 3, 8, 255, max = 5),
    Percent3("Attribute 3 %", 3, 0, 255, -100, 100, signed = true),
    Slots("Armor slots", 1, 0, 255, max = 4),
    TechniqueLevel("Technique level", 1, 8, 255, 1, 30),
    Meseta("Meseta (multiples of 10)", 2, 16, 65535, max = 655350, step = 10),
}
