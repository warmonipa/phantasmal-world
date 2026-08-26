package world.phantasmal.web.questEditor.rendering

import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapMarkerIconTests {
    @Test
    fun objects_map_to_their_legend_category() {
        assertEquals(MapMarkerCategory.Box, MapMarkerIcons.categoryForObject(ObjectType.RandomTypeBox1))
        assertEquals(MapMarkerCategory.SetBox, MapMarkerIcons.categoryForObject(ObjectType.FixedTypeBox))
        assertEquals(MapMarkerCategory.Switch, MapMarkerIcons.categoryForObject(ObjectType.ForestSwitch))
        assertEquals(MapMarkerCategory.Door, MapMarkerIcons.categoryForObject(ObjectType.ForestDoor))
        assertEquals(MapMarkerCategory.Door, MapMarkerIcons.categoryForObject(ObjectType.EnergyBarrier))
        assertEquals(MapMarkerCategory.Warp, MapMarkerIcons.categoryForObject(ObjectType.Warp))
        assertEquals(MapMarkerCategory.Warp, MapMarkerIcons.categoryForObject(ObjectType.TelepipeLocation))
        assertEquals(MapMarkerCategory.HealingRing, MapMarkerIcons.categoryForObject(ObjectType.HealRing))
        assertEquals(MapMarkerCategory.Trap, MapMarkerIcons.categoryForObject(ObjectType.ElementalTrap))
        assertEquals(MapMarkerCategory.QuestItem, MapMarkerIcons.categoryForObject(ObjectType.Item))
    }

    @Test
    fun logical_and_invisible_objects_are_not_drawn() {
        assertNull(MapMarkerIcons.categoryForObject(ObjectType.EnvSound))
        assertNull(MapMarkerIcons.categoryForObject(ObjectType.FogCollision))
        assertNull(MapMarkerIcons.categoryForObject(ObjectType.ScriptCollision))
        assertNull(MapMarkerIcons.categoryForObject(ObjectType.MapCollision))
        assertNull(MapMarkerIcons.categoryForObject(ObjectType.CharaCollision))
        assertNull(MapMarkerIcons.categoryForObject(ObjectType.Camera))
        assertNull(MapMarkerIcons.categoryForObject(ObjectType.PlayerSet))
        assertNull(MapMarkerIcons.categoryForObject(ObjectType.Particle))
    }

    @Test
    fun only_bosses_among_npcs_are_marked() {
        for (type in NpcType.values()) {
            if (type.boss) {
                assertEquals(
                    MapMarkerCategory.Boss,
                    MapMarkerIcons.categoryForNpc(type),
                    "Expected boss NpcType ${type.name} to map to the Boss category.",
                )
            } else {
                assertNull(
                    MapMarkerIcons.categoryForNpc(type),
                    "Expected non-boss NpcType ${type.name} to not be drawn.",
                )
            }
        }
    }
}
