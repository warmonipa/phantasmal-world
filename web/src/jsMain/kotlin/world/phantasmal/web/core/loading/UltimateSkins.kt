package world.phantasmal.web.core.loading

import world.phantasmal.psolib.fileFormats.quest.NpcType

/**
 * NPC types that ship a distinct Ultimate-difficulty skin, extracted as `<Name>.ult.nj` +
 * `<Name>.ult.xvm`. Any NPC not listed renders the same on Ultimate as on other difficulties, so
 * the Ultimate toggle keeps its normal asset.
 *
 * Single source of truth shared by the quest editor's EntityAssetLoader and the model viewer's
 * NpcAssetLoader — keep skin availability defined in exactly one place so the two can't drift.
 */
val NPCS_WITH_ULTIMATE_SKIN: Set<NpcType> = setOf(
    NpcType.EvilShark,
    NpcType.PalShark,
    NpcType.GuilShark,
    NpcType.Hildebear,
    NpcType.Hildeblue,
    NpcType.Mothmant,
    NpcType.GrassAssassin,
    NpcType.ChaosBringer,
    NpcType.Dimenian,
    NpcType.LaDimenian,
    NpcType.SoDimenian,
    NpcType.Dubchic,
    NpcType.Gilchic,
    NpcType.Garanz,
    NpcType.Canadine,
    // Sinow Berill has an Ultimate recolor; Sinow Spigell (stealth sinow) ships no Ultimate
    // archive, so it stays off this list and keeps its distinct normal skin.
    NpcType.SinowBerill,
    NpcType.PoisonLily,
    NpcType.NarLily,
    NpcType.ChaosSorcerer,
    NpcType.DarkBelra,
    NpcType.Booma,
    NpcType.Gobooma,
    NpcType.Gigobooma,
)
