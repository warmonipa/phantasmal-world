package world.phantasmal.psolib.fileFormats.quest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuestObjectWarpTests {
    @Test
    fun lab_map_warp_uses_the_newserv_identity() {
        assertEquals(ObjectType.LabMapWarp, objectTypeFromId(0x02BD))
        assertEquals("Lab Map Warp", ObjectType.LabMapWarp.uniqueName)
    }

    @Test
    fun teleporter_property_names_describe_the_runtime_semantics() {
        val standardProperties = listOf(
            "Destination floor",
            "Color (0=blue, 1=red)",
        )

        assertEquals(standardProperties, ObjectType.Teleporter.properties.map { it.name })
        assertEquals(
            listOf("Player set ID") + standardProperties,
            ObjectType.QuestWarp.properties.map { it.name },
        )
        assertEquals(standardProperties, ObjectType.TeleporterEp2.properties.map { it.name })
        assertEquals(
            standardProperties,
            ObjectType.WarpInBarbaRayRoom.properties.map { it.name },
        )
        assertEquals(
            listOf("Destination floor", "Color (<0=red, >=0=blue)"),
            ObjectType.RuinsTeleporter.properties.map { it.name },
        )
    }

    @Test
    fun specialized_intra_map_warp_properties_describe_the_runtime_semantics() {
        assertEquals(
            listOf(
                "Destination x",
                "Destination y",
                "Destination z",
                "Dst. rotation y",
                "Displayed floor number",
                "Floor display (<=0=show, >0=hide)",
            ),
            ObjectType.InstaWarp.properties.map { it.name },
        )
        assertEquals(
            listOf(
                "Destination x",
                "Destination y",
                "Destination z",
                "Dst. rotation y",
                "Destination text",
            ),
            ObjectType.LabMapWarp.properties.map { it.name },
        )
    }

    @Test
    fun all_intra_map_warps_expose_their_destination_fields() {
        val types = listOf(
            ObjectType.Warp,
            ObjectType.PrincipalWarp,
            ObjectType.RuinsWarpSiteToSite,
            ObjectType.InstaWarp,
            ObjectType.LabMapWarp,
        )

        for (type in types) {
            val warp = QuestObject(type, floorId = 1)

            assertEquals(40, warp.destinationPositionOffset, type.uniqueName)
            assertEquals(52, warp.destinationRotationYOffset, type.uniqueName)
        }
    }

    @Test
    fun all_cross_floor_teleporters_expose_their_destination_floor() {
        val types = listOf(
            ObjectType.Teleporter,
            ObjectType.QuestWarp,
            ObjectType.MainRagolTeleporterBattleInNextArea,
            ObjectType.RuinsTeleporter,
            ObjectType.TeleporterEp2,
            ObjectType.WarpInBarbaRayRoom,
        )

        for (type in types) {
            val teleporter = QuestObject(type, floorId = 1)

            assertEquals(52, teleporter.destinationFloorOffset, type.uniqueName)
            teleporter.destinationFloor = 7
            assertEquals(7, teleporter.destinationFloor, type.uniqueName)
        }
    }

    @Test
    fun teleporter_colors_follow_the_version_specific_param6_rules() {
        for (type in listOf(
            ObjectType.Teleporter,
            ObjectType.QuestWarp,
            ObjectType.TeleporterEp2,
            ObjectType.WarpInBarbaRayRoom,
        )) {
            val teleporter = QuestObject(type, floorId = 1)
            teleporter.data.setInt(60, 0)
            assertEquals(TeleporterColor.Blue, teleporter.teleporterColor, type.uniqueName)
            teleporter.data.setInt(60, 1)
            assertEquals(TeleporterColor.Red, teleporter.teleporterColor, type.uniqueName)
            teleporter.data.setInt(60, 2)
            assertNull(teleporter.teleporterColor, type.uniqueName)
        }

        val ruinsTeleporter = QuestObject(ObjectType.RuinsTeleporter, floorId = 1)
        ruinsTeleporter.data.setInt(60, 0)
        assertEquals(TeleporterColor.Blue, ruinsTeleporter.teleporterColor)
        ruinsTeleporter.data.setInt(60, -1)
        assertEquals(TeleporterColor.Red, ruinsTeleporter.teleporterColor)
    }
}
