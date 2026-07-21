package world.phantasmal.psolib.asm.dataFlowAnalysis

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.psolib.test.toInstructions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetFloorMappingsTests : LibTestSuite {
    @Test
    fun empty_segments_returns_empty_list() {
        val result = getFloorMappings(emptyList()) { error("should not be called") }
        assertTrue(result.isEmpty())
    }

    @Test
    fun bb_map_designate_extracts_floor_mappings() {
        val segments = toInstructions("""
            0:
                set_episode 1
                bb_map_designate 0, 18, 0, 0, 0
                bb_map_designate 17, 35, 0, 0, 0
                bb_map_designate 16, 35, 0, 1, 0
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        assertEquals(3, result.size)

        val floor0 = result.find { it.floorId == 0 }!!
        assertEquals(18, floor0.mapId)
        assertEquals(0, floor0.mapAreaId)
        assertEquals(0, floor0.mapVariation)
        assertEquals(Episode.II, floor0.mapEpisode)

        val floor17 = result.find { it.floorId == 17 }!!
        assertEquals(35, floor17.mapId)
        assertEquals(17, floor17.mapAreaId)
        assertEquals(0, floor17.mapVariation)
        assertEquals(Episode.II, floor17.mapEpisode)

        val floor16 = result.find { it.floorId == 16 }!!
        assertEquals(35, floor16.mapId)
        assertEquals(17, floor16.mapAreaId)
        assertEquals(1, floor16.mapVariation)
        assertEquals(Episode.II, floor16.mapEpisode)
    }

    @Test
    fun bb_designation_can_assign_floor_without_episode_default() {
        val segments = toInstructions("""
            0:
                set_episode 2
                bb_map_designate 17, 35, 0, 1, 0
                ret
        """.trimIndent())

        val mapping = getFloorMappings(segments) {
            ControlFlowGraph.create(segments)
        }.single()

        assertEquals(17, mapping.floorId)
        assertEquals(35, mapping.mapId)
        assertEquals(17, mapping.mapAreaId)
        assertEquals(Episode.II, mapping.mapEpisode)
        assertEquals(FloorMapSource.ExplicitDesignation, mapping.mapSource)
    }

    @Test
    fun later_bb_map_designate_overwrites_earlier_for_same_floor() {
        val segments = toInstructions("""
            0:
                set_episode 1
                bb_map_designate 0, 18, 0, 0, 0
                bb_map_designate 0, 35, 0, 2, 0
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        // Only one entry for floor 0, and it should be the later one (mapId=35, Tower)
        assertEquals(1, result.size)
        val floor0 = result[0]
        assertEquals(0, floor0.floorId)
        assertEquals(35, floor0.mapId)
        assertEquals(17, floor0.mapAreaId)
        assertEquals(2, floor0.mapVariation)
    }

    @Test
    fun set_floor_handler_before_bb_map_designate_does_not_win() {
        // set_floor_handler appears before bb_map_designate in label 0,
        // bb_map_designate overwrites it.
        val segments = toInstructions("""
            0:
                set_episode 1
                set_floor_handler 0, 300
                bb_map_designate 0, 35, 0, 1, 0
                ret
            300:
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        val floor0 = result.find { it.floorId == 0 }!!
        // bb_map_designate should win: mapId=35 (Tower), not the default from set_floor_handler
        assertEquals(35, floor0.mapId)
        assertEquals(17, floor0.mapAreaId)
        assertEquals(1, floor0.mapVariation)
    }

    @Test
    fun set_floor_handler_after_bb_map_designate_does_not_overwrite() {
        // bb_map_designate appears before set_floor_handler for the same floor,
        // set_floor_handler should NOT overwrite.
        val segments = toInstructions("""
            0:
                set_episode 1
                bb_map_designate 0, 35, 0, 1, 0
                set_floor_handler 0, 300
                ret
            300:
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        val floor0 = result.find { it.floorId == 0 }!!
        assertEquals(35, floor0.mapId)
        assertEquals(17, floor0.mapAreaId)
        assertEquals(1, floor0.mapVariation)
    }

    @Test
    fun set_floor_handler_floor_is_bound_by_episode_map_initialization() {
        // Floor 0 has both set_floor_handler and bb_map_designate -> bb_map_designate wins.
        // Floor 17 only has set_floor_handler -> should be filled in.
        val segments = toInstructions("""
            0:
                set_episode 1
                set_floor_handler 0, 300
                set_floor_handler 17, 310
                bb_map_designate 0, 18, 0, 0, 0
                ret
            300:
                ret
            310:
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        // Floor 0: bb_map_designate wins
        val floor0 = result.find { it.floorId == 0 }!!
        assertEquals(18, floor0.mapId)
        assertEquals(0, floor0.mapAreaId)

        // set_floor_handler only marks floor 17 as used. The Episode II default map table
        // (the editor equivalent of client init_episode_maps) binds it to map 35, Tower.
        val floor17 = result.find { it.floorId == 17 }!!
        assertEquals(35, floor17.mapId)
        assertEquals(17, floor17.mapAreaId)
        assertEquals(0, floor17.mapVariation)
        assertEquals(Episode.II, floor17.mapEpisode)
        assertEquals(FloorMapSource.EpisodeDefault, floor17.mapSource)
    }

    @Test
    fun returns_flat_list_not_grouped() {
        val segments = toInstructions("""
            0:
                set_episode 1
                bb_map_designate 0, 18, 0, 0, 0
                bb_map_designate 17, 35, 0, 0, 0
                bb_map_designate 16, 35, 0, 1, 0
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        // Mappings are per logical floor, not grouped by mapAreaId.
        // Two floors select mapAreaId=17 but remain separate entries.
        assertEquals(3, result.size)
        assertEquals(2, result.count { it.mapAreaId == 17 })
    }

    @Test
    fun bb_map_designate_type_1_keeps_default_map_and_uses_offline_template() {
        val segments = toInstructions("""
            0:
                set_episode 1
                bb_map_designate 0, 35, 1, 2, 3
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        assertEquals(1, result.size)
        assertEquals(18, result[0].mapId)
        assertEquals(0, result[0].mapVariation)
        assertEquals(FloorDataSource.OfflineTemplate, result[0].dataSource)
        assertEquals(FloorMapSource.EpisodeDefault, result[0].mapSource)
    }

    @Test
    fun bb_map_designate_type_2_overwrites_full_mapping_and_uses_online_template() {
        val segments = toInstructions("""
            0:
                set_episode 1
                bb_map_designate 16, 35, 2, 1, 3
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        assertEquals(1, result.size)
        assertEquals(35, result[0].mapId)
        assertEquals(1, result[0].mapVariation)
        assertEquals(3, result[0].objectSetVariation)
        assertEquals(FloorDataSource.OnlineTemplate, result[0].dataSource)
        assertEquals(FloorMapSource.ExplicitDesignation, result[0].mapSource)
    }

    @Test
    fun bb_map_designate_type_0_forces_entity_set_variation_to_zero() {
        val segments = toInstructions("""
            0:
                set_episode 1
                bb_map_designate 16, 35, 0, 1, 9
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        assertEquals(0, result.single().objectSetVariation)
        assertEquals(FloorDataSource.QuestDat, result.single().dataSource)
    }

    @Test
    fun bb_map_designate_type_3_keeps_default_map_and_loads_no_entities() {
        val segments = toInstructions("""
            0:
                set_episode 1
                bb_map_designate 0, 35, 3, 2, 3
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        assertEquals(18, result.single().mapId)
        assertEquals(FloorDataSource.None, result.single().dataSource)
    }

    @Test
    fun bb_map_designate_rejects_invalid_map_before_applying_type() {
        val segments = toInstructions("""
            0:
                set_episode 1
                bb_map_designate 5, 21, 0, 2, 0
                bb_map_designate 5, 255, 1, 9, 9
                bb_map_designate 6, 255, 3, 9, 9
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        val floor5 = result.single { it.floorId == 5 }
        assertEquals(21, floor5.mapId)
        assertEquals(2, floor5.mapVariation)
        assertEquals(FloorDataSource.QuestDat, floor5.dataSource)
        assertEquals(FloorMapSource.ExplicitDesignation, floor5.mapSource)

        val floor6 = result.single { it.floorId == 6 }
        assertEquals(24, floor6.mapId)
        assertEquals(0, floor6.mapVariation)
        assertEquals(FloorDataSource.QuestDat, floor6.dataSource)
        assertEquals(FloorMapSource.EpisodeDefault, floor6.mapSource)
    }

    @Test
    fun map_designate_ex_rejects_invalid_map_before_applying_type() {
        val segments = toInstructions("""
            0:
                set_episode 0
                leti r20, 5
                leti r21, 255
                leti r22, 1
                map_designate_ex r20
                ret
        """.trimIndent())

        val mapping = getFloorMappings(segments) {
            ControlFlowGraph.create(segments)
        }.single()

        assertEquals(5, mapping.mapId)
        assertEquals(FloorDataSource.QuestDat, mapping.dataSource)
        assertEquals(FloorMapSource.EpisodeDefault, mapping.mapSource)
    }

    @Test
    fun dat_only_floor_uses_episode_default_mapping() {
        val result = getFloorMappings(
            instructionSegments = emptyList(),
            usedFloorIds = setOf(1),
        ) {
            error("CFG should not be created")
        }

        assertEquals(1, result.size)
        assertEquals(1, result[0].floorId)
        assertEquals(1, result[0].mapId)
        assertEquals(FloorMapSource.EpisodeDefault, result[0].mapSource)
    }

    @Test
    fun bb_v4_legacy_map_designate_uses_floor_as_map_id_and_ignores_fourth_register() {
        val segments = toInstructions("""
            0:
                set_episode 1
                leti r10, 16
                leti r11, 2
                leti r12, 1
                leti r13, 9
                map_designate r10
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        assertEquals(16, result.single().mapId)
        assertEquals(1, result.single().mapVariation)
        assertEquals(0, result.single().objectSetVariation)
        assertEquals(FloorDataSource.OnlineTemplate, result.single().dataSource)
    }

    @Test
    fun dc_v2_map_designate_type_2_uses_fourth_register_as_object_set_variation() {
        val segments = toInstructions("""
            0:
                set_episode 0
                leti r10, 1
                leti r11, 2
                leti r12, 0
                leti r13, 9
                map_designate r10
                ret
        """.trimIndent())

        val result = getFloorMappings(
            instructionSegments = segments,
            version = Version.DC_V2,
        ) {
            ControlFlowGraph.create(segments)
        }

        assertEquals(1, result.single().mapId)
        assertEquals(0, result.single().mapVariation)
        assertEquals(9, result.single().objectSetVariation)
        assertEquals(FloorDataSource.OnlineTemplate, result.single().dataSource)
    }

    @Test
    fun gc_v3_map_designate_type_2_forces_object_set_variation_to_zero() {
        val segments = toInstructions("""
            0:
                set_episode 0
                leti r10, 1
                leti r11, 2
                leti r12, 0
                leti r13, 9
                map_designate r10
                ret
        """.trimIndent())

        val result = getFloorMappings(
            instructionSegments = segments,
            version = Version.GC_V3,
        ) {
            ControlFlowGraph.create(segments)
        }

        assertEquals(0, result.single().objectSetVariation)
        assertEquals(FloorDataSource.OnlineTemplate, result.single().dataSource)
    }

    @Test
    fun map_designate_ex_type_2_uses_map_and_object_set_variations() {
        val segments = toInstructions("""
            0:
                set_episode 0
                leti r20, 1
                leti r21, 35
                leti r22, 2
                leti r23, 4
                leti r24, 7
                map_designate_ex r20
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        assertEquals(1, result.single().floorId)
        assertEquals(35, result.single().mapId)
        assertEquals(17, result.single().mapAreaId)
        assertEquals(4, result.single().mapVariation)
        assertEquals(7, result.single().objectSetVariation)
        assertEquals(Episode.II, result.single().mapEpisode)
        assertEquals(FloorDataSource.OnlineTemplate, result.single().dataSource)
        assertEquals(FloorMapSource.ExplicitDesignation, result.single().mapSource)
    }

    @Test
    fun map_designate_ex_type_0_forces_entity_set_variation_to_zero() {
        val segments = toInstructions("""
            0:
                set_episode 0
                leti r20, 1
                leti r21, 2
                leti r22, 0
                leti r23, 4
                leti r24, 7
                map_designate_ex r20
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        assertEquals(0, result.single().objectSetVariation)
        assertEquals(FloorDataSource.QuestDat, result.single().dataSource)
    }

    @Test
    fun floor_17_is_valid_and_floor_18_is_ignored() {
        val segments = toInstructions("""
            0:
                set_episode 1
                bb_map_designate 17, 35, 0, 0, 0
                bb_map_designate 18, 35, 0, 0, 0
                ret
        """.trimIndent())

        val result = getFloorMappings(
            instructionSegments = segments,
            usedFloorIds = setOf(17, 18),
        ) {
            ControlFlowGraph.create(segments)
        }

        assertEquals(listOf(17), result.map(FloorMapping::floorId))
    }

    @Test
    fun map_id_upper_bound_is_version_specific() {
        fun resolve(version: Version, validMapId: Int, invalidMapId: Int): List<FloorMapping> {
            val segments = toInstructions("""
                0:
                    set_episode 0
                    leti r20, 1
                    leti r21, $validMapId
                    leti r22, 0
                    leti r23, 0
                    leti r24, 0
                    map_designate_ex r20
                    leti r20, 2
                    leti r21, $invalidMapId
                    map_designate_ex r20
                    ret
            """.trimIndent())
            return getFloorMappings(segments, version = version) {
                ControlFlowGraph.create(segments)
            }
        }

        fun assertBoundary(version: Version, validMapId: Int, invalidMapId: Int) {
            val result = resolve(version, validMapId, invalidMapId)
            assertEquals(validMapId, result.single { it.floorId == 1 }.mapId)
            val invalidDesignationFloor = result.single { it.floorId == 2 }
            assertEquals(2, invalidDesignationFloor.mapId)
            assertEquals(FloorMapSource.EpisodeDefault, invalidDesignationFloor.mapSource)
        }

        assertBoundary(Version.DC_V2, 17, 18)
        assertBoundary(Version.GC_V3, 35, 36)
        assertBoundary(Version.BB_V4, 46, 47)
    }

    @Test
    fun unresolved_designation_register_does_not_create_mapping() {
        val segments = toInstructions("""
            0:
                set_episode 0
                map_designate_ex r20
                ret
        """.trimIndent())

        val result = getFloorMappings(segments) { ControlFlowGraph.create(segments) }

        assertTrue(result.isEmpty())
    }

    @Test
    fun serialized_map_fields_keep_legacy_wire_names() {
        val descriptor = FloorMapping.serializer().descriptor

        assertEquals("areaId", descriptor.getElementName(2))
        assertEquals("variantId", descriptor.getElementName(3))
    }
}
