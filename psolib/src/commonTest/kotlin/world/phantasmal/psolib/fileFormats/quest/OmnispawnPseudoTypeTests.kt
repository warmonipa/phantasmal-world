package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.buffer.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests for the Omnispawn explicit (Ephinea) pseudo-types 0xE2-0xE5, which force one side
 * of an area/skin-gated shared family (0xE0 Epsilon/Sinow Zoa, 0x61 Poison/Del Lily). The
 * typeId itself is the discriminator, so detection must ignore skin/area/special.
 */
class OmnispawnPseudoTypeTests {

    /** Create a raw QuestNpc with the given header values. */
    private fun createRawNpc(
        episode: Episode,
        floorId: Int,
        typeId: Int,
        skin: Int = 0,
        special: Boolean = false,
    ): QuestNpc {
        val data = Buffer.withSize(NPC_BYTE_SIZE)
        data.setShort(0, typeId.toShort())   // typeId at offset 0
        data.setFloat(48, if (special) 1f else 0f) // special at offset 48
        data.setInt(64, skin)                // skin at offset 64
        return QuestNpc(episode, floorId, data)
    }

    @Test
    fun typeId_0xE2_resolves_to_SinowZoaOmni() {
        assertEquals(NpcType.SinowZoaOmni, createRawNpc(Episode.II, floorId = 11, typeId = 0xE2).type)
    }

    @Test
    fun typeId_0xE3_resolves_to_EpsilonOmni() {
        assertEquals(NpcType.EpsilonOmni, createRawNpc(Episode.II, floorId = 17, typeId = 0xE3).type)
    }

    @Test
    fun typeId_0xE4_resolves_to_PoisonLilyOmni() {
        assertEquals(NpcType.PoisonLilyOmni, createRawNpc(Episode.I, floorId = 3, typeId = 0xE4).type)
    }

    @Test
    fun typeId_0xE5_resolves_to_DelLilyOmni() {
        assertEquals(NpcType.DelLilyOmni, createRawNpc(Episode.II, floorId = 17, typeId = 0xE5).type)
    }

    @Test
    fun detection_ignores_skin_area_and_special() {
        // The pseudo-type is the sole discriminator: a 0xE2 placed outside Seabed with a
        // non-zero skin and special flag must still resolve to Sinow Zoa.
        val npc = createRawNpc(Episode.I, floorId = 1, typeId = 0xE2, skin = 1, special = true)
        assertEquals(NpcType.SinowZoaOmni, npc.type)
    }

    @Test
    fun pseudo_types_are_distinct_from_stock_siblings() {
        assertNotEquals(NpcType.SinowZoa, NpcType.SinowZoaOmni)
        assertNotEquals(NpcType.Epsilon, NpcType.EpsilonOmni)
        assertNotEquals(NpcType.PoisonLily, NpcType.PoisonLilyOmni)
        assertNotEquals(NpcType.DelLily, NpcType.DelLilyOmni)
    }

    @Test
    fun stock_0xE0_still_resolves_by_skin_and_area() {
        // Regression guard: registering the pseudo-types must not perturb stock 0xE0 resolution.
        assertEquals(NpcType.SinowZoa, createRawNpc(Episode.II, floorId = 10, typeId = 0xE0, skin = 0).type)
        assertEquals(NpcType.SinowZele, createRawNpc(Episode.II, floorId = 10, typeId = 0xE0, skin = 1).type)
        assertEquals(NpcType.Epsilon, createRawNpc(Episode.II, floorId = 17, typeId = 0xE0, skin = 0).type)
    }

    @Test
    fun roundtrip_through_constructor_writes_pseudo_typeId_and_skin0() {
        // Selecting a pseudo-type in the editor writes its pseudo typeId with skin 0, and
        // re-reading the data resolves back to the same type.
        val cases = listOf(
            NpcType.SinowZoaOmni to 226,
            NpcType.EpsilonOmni to 227,
            NpcType.PoisonLilyOmni to 228,
            NpcType.DelLilyOmni to 229,
        )
        for ((type, expectedTypeId) in cases) {
            val episode = type.episode ?: Episode.II
            val npc = QuestNpc(type, episode, floorId = type.areaIds.first(), wave = 0)
            assertEquals(expectedTypeId, npc.typeId.toInt(), "$type should write typeId $expectedTypeId")
            assertEquals(0, npc.skin, "$type should write skin 0")
            assertEquals(type, npc.type, "$type should round-trip back to itself")
        }
    }
}
