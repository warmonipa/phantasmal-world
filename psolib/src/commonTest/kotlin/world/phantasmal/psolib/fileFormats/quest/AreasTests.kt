package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.Episode
import kotlin.test.Test
import kotlin.test.assertEquals

class AreasTests {
    private val ep2 = getAreasForEpisode(Episode.II)

    @Test
    fun ep2_jungle_north_is_floor_6_and_jungle_east_is_floor_7() {
        assertEquals("Jungle Area North", ep2[6].name)
        assertEquals(0x18, ep2[6].mapId)
        assertEquals("Jungle Area East", ep2[7].name)
        assertEquals(0x19, ep2[7].mapId)
    }

    @Test
    fun ep2_seaside_night_has_one_variant() {
        val area = ep2[16]
        assertEquals("Seaside Area at Night", area.name)
        assertEquals(0x22, area.mapId)
        assertEquals(1, area.areaVariants.size)
    }
}
