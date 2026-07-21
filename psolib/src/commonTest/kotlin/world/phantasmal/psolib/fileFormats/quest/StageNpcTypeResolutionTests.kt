package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.buffer.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests that StageNPC (typeId=0x33) resolves to the correct monster NpcType
 * via the NPC51 table, and that the resolved type matches the regular monster type.
 */
class StageNpcTypeResolutionTests {

    /**
     * Create a raw StageNPC with independent logical-floor and resolved map-area identities.
     */
    private fun createStageNpc(
        episode: Episode,
        floorId: Int,
        mapAreaId: Int,
        subtype: Int,
    ): QuestNpc {
        val data = Buffer.withSize(NPC_BYTE_SIZE)
        // typeId = 0x33 (StageNPC) at offset 0
        data.setShort(0, 0x33)
        // subtype (character index) at offset 32 (rotation.x field)
        data.setInt(32, subtype)

        val npc = QuestNpc(episode, floorId, data)
        npc.mapAreaId = mapAreaId
        return npc
    }

    @Test
    fun stageNpc_in_tower_subtype9_resolves_to_Epsilon() {
        // EP2 Tower: mapAreaId=17, mapId=0x23=35, subtype 9 = Epsilon
        val npc = createStageNpc(Episode.II, floorId = 17, mapAreaId = 17, subtype = 9)
        assertEquals(NpcType.Epsilon, npc.type, "StageNPC subtype 9 in Tower should resolve to Epsilon")
    }

    @Test
    fun stageNpc_in_tower_subtype4_resolves_to_GiGue() {
        // EP2 Tower: subtype 4 = GiGue
        val npc = createStageNpc(Episode.II, floorId = 17, mapAreaId = 17, subtype = 4)
        assertEquals(NpcType.GiGue, npc.type, "StageNPC subtype 4 in Tower should resolve to GiGue")
    }

    @Test
    fun stageNpc_in_ep1_ruins_subtype7_resolves_to_ChaosSorcerer() {
        // EP1 Ruins 1: mapAreaId=8, mapId=0x08=8, subtype 7 = ChaosSorcerer
        val npc = createStageNpc(Episode.I, floorId = 8, mapAreaId = 8, subtype = 7)
        assertEquals(NpcType.ChaosSorcerer, npc.type, "StageNPC subtype 7 in Ruins 1 should resolve to ChaosSorcerer")
    }

    @Test
    fun stageNpc_in_tower_subtype6_resolves_to_IllGill() {
        // EP2 Tower: subtype 6 = IllGill
        val npc = createStageNpc(Episode.II, floorId = 17, mapAreaId = 17, subtype = 6)
        assertEquals(NpcType.IllGill, npc.type, "StageNPC subtype 6 in Tower should resolve to IllGill")
    }

    @Test
    fun stageNpc_in_tower_subtype8_resolves_to_Delbiter() {
        // EP2 Tower: subtype 8 = Delbiter
        val npc = createStageNpc(Episode.II, floorId = 17, mapAreaId = 17, subtype = 8)
        assertEquals(NpcType.Delbiter, npc.type, "StageNPC subtype 8 in Tower should resolve to Delbiter")
    }

    @Test
    fun stageNpc_with_invalid_subtype_resolves_to_NpcEnemy() {
        // subtype > 15 should fall back to NpcEnemy
        val npc = createStageNpc(Episode.II, floorId = 17, mapAreaId = 17, subtype = 99)
        assertEquals(NpcType.NpcEnemy, npc.type, "StageNPC with invalid subtype should resolve to NpcEnemy")
    }

    @Test
    fun stageNpc_with_unmapped_area_resolves_to_NpcEnemy() {
        // mapAreaId that doesn't map to any known area → mapId=0 → NPC51_TABLE[0] doesn't exist
        val npc = createStageNpc(Episode.II, floorId = 99, mapAreaId = 99, subtype = 4)
        assertEquals(NpcType.NpcEnemy, npc.type, "StageNPC with unmapped area should resolve to NpcEnemy")
    }

    @Test
    fun stageNpc_type_matches_regular_npc_type() {
        // Verify that StageNPC Epsilon is the exact same NpcType as regular Epsilon
        val stageNpc = createStageNpc(Episode.II, floorId = 17, mapAreaId = 17, subtype = 9)
        val regularNpc = QuestNpc(NpcType.Epsilon, Episode.II, floorId = 17, wave = 0)

        assertEquals(regularNpc.type, stageNpc.type,
            "StageNPC resolved type should be identical to regular NPC type")
    }

    @Test
    fun stageNpc_without_mapAreaId_update_uses_floorId_as_default() {
        // mapAreaId defaults to floorId for ordinary quests where both IDs coincide.
        val data = Buffer.withSize(NPC_BYTE_SIZE)
        data.setShort(0, 0x33)
        data.setInt(32, 9) // Epsilon in Tower

        val npc = QuestNpc(Episode.II, floorId = 17, data)
        assertEquals(NpcType.Epsilon, npc.type,
            "StageNPC should resolve correctly when mapAreaId defaults to floorId")
    }

    @Test
    fun stageNpc_with_wrong_mapAreaId_resolves_incorrectly() {
        // StageNPC subtype 9 in Tower = Epsilon, but mapAreaId=6 selects Jungle East.
        val npc = createStageNpc(Episode.II, floorId = 17, mapAreaId = 6, subtype = 9)
        assertNotEquals(NpcType.Epsilon, npc.type,
            "StageNPC with wrong mapAreaId should NOT resolve to the expected type")
    }
}
