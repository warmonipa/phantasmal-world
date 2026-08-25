package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.test.LibTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ObjectTypeTests : LibTestSuite {
    @Test
    fun season_object_types_map_to_their_lobby_event() {
        assertEquals(LobbyEvent.Christmas, ObjectType.ChristmasTree.lobbyEvent)
        assertEquals(LobbyEvent.Christmas, ObjectType.ChristmasWreath.lobbyEvent)
        assertEquals(LobbyEvent.Valentine, ObjectType.ValentinesHeart.lobbyEvent)
        assertEquals(LobbyEvent.Easter, ObjectType.EasterEgg.lobbyEvent)
        assertEquals(LobbyEvent.Halloween, ObjectType.HalloweenPumpkin.lobbyEvent)
        assertEquals(LobbyEvent.Sonic, ObjectType.Sonic.lobbyEvent)
        assertEquals(LobbyEvent.NewYear, ObjectType.TwentyFirstCentury.lobbyEvent)
        assertEquals(LobbyEvent.NewYear, ObjectType.Firework.lobbyEvent)
    }

    @Test
    fun welcome_board_has_no_lobby_event_because_its_event_is_unknown() {
        // WelcomeBoard is a city-lobby object but its event ID is not known, so it is
        // treated as always-visible (lobbyEvent = null).
        assertNull(ObjectType.WelcomeBoard.lobbyEvent)
    }

    @Test
    fun non_season_object_types_have_no_lobby_event() {
        assertNull(ObjectType.PlayerSet.lobbyEvent)
        assertNull(ObjectType.Teleporter.lobbyEvent)
        assertNull(ObjectType.Unknown.lobbyEvent)
    }

    @Test
    fun exactly_eight_object_types_are_seasonal() {
        // Guard: if a TObjCity_Season_* type is added/removed, update the mapping test above.
        val seasonal = ObjectType.entries.filter { it.lobbyEvent != null }
        assertEquals(8, seasonal.size, "Update the seasonal mapping test when this changes.")
    }

    @Test
    fun every_script_object_exposes_its_script_label_property() {
        val scriptObjectTypes = listOf(
            ObjectType.ScriptCollision,
            ObjectType.ScriptCollisionA,
            ObjectType.TargetableObject,
            ObjectType.ChatSensor,
            ObjectType.ForestConsole,
            ObjectType.RicoMessagePod,
            ObjectType.ComputerLikeCalus,
            ObjectType.RuinsCrystal,
            ObjectType.VRLink,
            ObjectType.GBAStation,
            ObjectType.TalkLinkToSupport,
            ObjectType.QuestCollision2,
        )

        for (type in scriptObjectTypes) {
            val obj = QuestObject(type, floorId = 0)
            assertNotNull(
                type.properties.find { it.offset == obj.possibleScriptLabelOffset },
                "${type.uniqueName} does not expose its script label property.",
            )
        }
    }

    @Test
    fun conditional_script_object_modes_control_the_active_label() {
        val targetable = QuestObject(ObjectType.TargetableObject, floorId = 0)
        targetable.data.setInt(60, 0)
        assertNull(targetable.activeScriptLabel)
        targetable.data.setInt(60, 100)
        assertEquals(100, targetable.activeScriptLabel)

        val chatSensor = QuestObject(ObjectType.ChatSensor, floorId = 0)
        chatSensor.data.setInt(52, 200)
        chatSensor.data.setInt(28, 1)
        assertNull(chatSensor.activeScriptLabel)
        chatSensor.data.setInt(28, 0)
        assertEquals(200, chatSensor.activeScriptLabel)

        for (type in listOf(ObjectType.TalkLinkToSupport, ObjectType.QuestCollision2)) {
            val obj = QuestObject(type, floorId = 0)
            obj.data.setInt(52, 300)
            obj.data.setInt(56, 1)
            assertNull(obj.activeScriptLabel)
            obj.data.setInt(56, 0)
            assertEquals(300, obj.activeScriptLabel)
        }
    }

    @Test
    fun corrected_object_ids_follow_newserv_semantics() {
        assertEquals(ObjectType.LobbyGameMenuCollision, objectTypeFromId(384))
        assertEquals(ObjectType.Seagull, objectTypeFromId(530))
        assertEquals(ObjectType.JungleDesign, objectTypeFromId(531))
        assertEquals(ObjectType.QuestCollision2, objectTypeFromId(698))

        assertEquals("Lobby Game Menu Collision", ObjectType.LobbyGameMenuCollision.uniqueName)
        assertEquals("Seagull", ObjectType.Seagull.uniqueName)
        assertEquals("Jungle Design", ObjectType.JungleDesign.uniqueName)
        assertEquals("Quest Collision 2", ObjectType.QuestCollision2.uniqueName)
        assertEquals(
            "Script manager (<=0=quest, >0=free play)",
            ObjectType.QuestCollision2.properties.single { it.offset == 56 }.name,
        )
    }

    @Test
    fun fog_objects_expose_the_client_parameters() {
        assertEquals(
            listOf(40, 52, 56),
            ObjectType.FogCollision.properties.map { it.offset },
        )
        assertEquals(
            listOf(40, 48, 52, 56, 60),
            ObjectType.FogCollisionSW.properties.map { it.offset },
        )
    }
}
