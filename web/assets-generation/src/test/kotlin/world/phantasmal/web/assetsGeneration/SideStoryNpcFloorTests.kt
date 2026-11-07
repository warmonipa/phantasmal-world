package world.phantasmal.web.assetsGeneration

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.quest.parseQstToQuest

/** Independent checks for the creation sites that exposed lost callee suspension paths. */
class SideStoryNpcFloorTests {
    @Test
    fun returning_guard_branches_do_not_restrict_persistent_caller_continuations() {
        val depths = load(1, 25, "e87d82625be5c75867c1cb696a7d230b7d6c1e3836ba5bae4e0abd59ec43fb58")
        val allFloors = (0 until 18).toSet()
        for ((template, x, angle) in listOf(Triple(36, 2700, -90), Triple(26, 1660, 0), Triple(27, 1610, 0))) {
            val spawn = depths.scriptNpcSpawns.single {
                it.templateIndex == template && it.x == x && it.angle == angle
            }
            assertEquals(allFloors, spawn.executionFloorIds, "From the Depths: template $template")
        }
        val fireSwirl = load(1, 26, "8dc6d7df116a2147b30b1e3f1f37e97a497cce0fb491822f872befd38fd6edd4")
        val alicia = fireSwirl.scriptNpcSpawns.single { it.templateIndex == 16 && it.x == 225 }
        assertEquals(allFloors, alicia.executionFloorIds)
    }

    @Test
    fun seat_of_the_heart_hopkins_is_created_after_sync_in_a_persistent_call_path() {
        val quest = load(2, 27, "554b12f8f5093582d1a6723852b6b418161ad82b8fba98824fb482d83f238558")
        // newserv BB disassembly: label 0x07EC, sync at 0x2242C, creation at 0x22457.
        val hopkins = quest.scriptNpcSpawns.single { it.templateIndex == 28 && it.x == 610 }
        assertEquals((0 until 18).toSet(), hopkins.executionFloorIds)
    }

    private fun load(episode: Int, id: Int, sha256: String) = Buffer.fromResource(
        "/ephinea/ship-config/quest/episode_$episode/story/side_story/quest${id}_e.qst"
    ).let { buffer ->
        val hash = MessageDigest.getInstance("SHA-256").digest(buffer.byteArray)
            .joinToString("") { "%02x".format(it) }
        assertEquals(sha256, hash, "Evidence fixture changed")
        parseQstToQuest(buffer.cursor(), lenient = false).unwrap().quest
    }
}
