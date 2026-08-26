package world.phantasmal.web.questEditor.rendering

import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.ObjectType

/** Shape drawn for a marker category (both on the map and in the legend swatch). */
enum class MapMarkerShape {
    CIRCLE,
    DIAMOND,
    SQUARE,
    RING,
    TRIANGLE,
    STAR,
}

/**
 * Predefined 2D map legend categories, aligned to the spirit of the pioneer2 wiki's shared
 * `Map_Legend.png`. This enum is the single source of truth: both the on-map marker sprites and
 * the legend swatches read a category's [colorHex]/[shape]/[label], so changing a symbol here
 * updates the map and the legend together.
 */
enum class MapMarkerCategory(
    val label: String,
    val colorHex: Int,
    val shape: MapMarkerShape,
) {
    Boss("Boss", 0xFF3B30, MapMarkerShape.DIAMOND),
    Box("Box", 0x35C759, MapMarkerShape.SQUARE),
    SetBox("Set Box", 0xFFCC00, MapMarkerShape.SQUARE),
    Switch("Switch", 0x34C759, MapMarkerShape.CIRCLE),
    Door("Door / Barrier", 0xE0E0E0, MapMarkerShape.SQUARE),
    Warp("Warp", 0xFFD60A, MapMarkerShape.TRIANGLE),
    HealingRing("Healing Ring", 0x30D158, MapMarkerShape.RING),
    Trap("Trap", 0xFF453A, MapMarkerShape.DIAMOND),
    QuestItem("Quest Item", 0xFFFFFF, MapMarkerShape.STAR),
}

/**
 * Classifies quest entities into [MapMarkerCategory]s for the 2D map export.
 *
 * Only physical objects that belong to a legend category are drawn. Logical/invisible objects
 * (sound emitters, collision volumes, cameras, particles, spawn points, ...) return `null` and
 * are skipped so they don't clutter the map. Regular enemies/minions/friendly NPCs also return
 * `null`; only bosses are marked.
 */
object MapMarkerIcons {
    fun categoryForNpc(type: NpcType): MapMarkerCategory? =
        if (type.boss) MapMarkerCategory.Boss else null

    fun categoryForObject(type: ObjectType): MapMarkerCategory? = when (type) {
        ObjectType.RandomTypeBox1,
        ObjectType.EnemyBoxGrey,
        ObjectType.EnemyBoxBrown,
        ObjectType.EmptyTypeBox,
        ObjectType.RandomBoxTypeRuins,
        ObjectType.EnemyTypeBoxYellow,
        ObjectType.EnemyTypeBoxBlue,
        ObjectType.EmptyTypeBoxBlue,
        ObjectType.DesertFixedTypeBoxBreakableCrystals,
        -> MapMarkerCategory.Box

        ObjectType.FixedTypeBox,
        ObjectType.FixedBoxTypeRuins,
        ObjectType.ItemBoxCca,
        ObjectType.SpecialBoxCca,
        -> MapMarkerCategory.SetBox

        ObjectType.ForestSwitch,
        ObjectType.ForestLaserFenceSwitch,
        ObjectType.RuinsSwitch,
        ObjectType.RuinsFenceSwitch,
        ObjectType.BigCcaDoorSwitch,
        ObjectType.TouchPlateObject,
        -> MapMarkerCategory.Switch

        ObjectType.ShopDoor,
        ObjectType.HuntersGuildDoor,
        ObjectType.TeleporterDoor,
        ObjectType.MedicalCenterDoor,
        ObjectType.ForestDoor,
        ObjectType.BlackSlidingDoor,
        ObjectType.SwitchNoneDoor,
        ObjectType.CavesNormalDoor,
        ObjectType.Caves4ButtonDoor,
        ObjectType.CavesSwitchDoor,
        ObjectType.MinesDoor,
        ObjectType.MinesSwitchDoor,
        ObjectType.Ruins1Door,
        ObjectType.Ruins3Door,
        ObjectType.Ruins2Door,
        ObjectType.Ruins11ButtonDoor,
        ObjectType.Ruins21ButtonDoor,
        ObjectType.Ruins31ButtonDoor,
        ObjectType.Ruins4ButtonDoor,
        ObjectType.Ruins2ButtonDoor,
        ObjectType.SpaceshipDoor,
        ObjectType.TempleNormalDoor,
        ObjectType.FourSwitchTempleDoor,
        ObjectType.FourButtonSpaceshipDoor,
        ObjectType.CcaDoor,
        ObjectType.BigCcaDoor,
        ObjectType.SeabedDoorWithBlueEdges,
        ObjectType.SeabedDoorAlwaysOpenNonTriggerable,
        ObjectType.LabGlassWindowDoor,
        ObjectType.LabTeleporterDoor,
        ObjectType.LobbyScreenDoor,
        ObjectType.Ep4TestDoor,
        ObjectType.LaserFence,
        ObjectType.LaserSquareFence,
        ObjectType.LaserFenceEx,
        ObjectType.LaserSquareFenceEx,
        ObjectType.RuinsLaserFence4x2,
        ObjectType.RuinsLaserFence6x2,
        ObjectType.RuinsLaserFence4x4,
        ObjectType.RuinsLaserFence6x4,
        ObjectType.EnergyBarrier,
        -> MapMarkerCategory.Door

        ObjectType.Warp,
        ObjectType.Teleporter,
        ObjectType.BossTeleporter,
        ObjectType.QuestWarp,
        ObjectType.PrincipalWarp,
        ObjectType.RuinsTeleporter,
        ObjectType.RuinsWarpSiteToSite,
        ObjectType.MainRagolTeleporter,
        ObjectType.LobbyTeleporter,
        ObjectType.CcaAreaTeleporter,
        ObjectType.TeleporterEp2,
        ObjectType.InstaWarp,
        ObjectType.LabMapWarp,
        ObjectType.AreaWarpEndingJung,
        ObjectType.WarpInBarbaRayRoom,
        ObjectType.LobbyWarpObject,
        ObjectType.TelepipeLocation,
        -> MapMarkerCategory.Warp

        ObjectType.HealRing -> MapMarkerCategory.HealingRing

        ObjectType.ElementalTrap,
        ObjectType.StatusTrap,
        ObjectType.HealTrap,
        ObjectType.LargeElementalTrap,
        ObjectType.RuinsPillarTrap,
        ObjectType.PopupTrapNoTech,
        ObjectType.PopupTrapsTechs,
        ObjectType.CaptureTrap,
        ObjectType.Poison,
        ObjectType.RuinsPoisonBlob,
        -> MapMarkerCategory.Trap

        ObjectType.Item -> MapMarkerCategory.QuestItem

        else -> null
    }
}
