package world.phantasmal.web.core.models

import kotlin.test.Test
import kotlin.test.assertEquals
import world.phantasmal.psolib.fileFormats.quest.ObjectType

class ObjectVisualClassTests {
    @Test
    fun non_static_visual_class_distribution_is_explicit() {
        val counts = ObjectType.entries.groupingBy(::objectVisualClass).eachCount()

        assertEquals(1, counts[ObjectVisualClass.EditorMarker])
        assertEquals(36, counts[ObjectVisualClass.InvisibleLogic])
        assertEquals(21, counts[ObjectVisualClass.RuntimeVisual])
        assertEquals(12, counts[ObjectVisualClass.UnavailableModel])
        assertEquals(12, counts[ObjectVisualClass.Unverified])
    }

    @Test
    fun distinguishes_why_objects_do_not_have_static_models() {
        assertEquals(ObjectVisualClass.StaticModel, objectVisualClass(ObjectType.ForestDoor))
        assertEquals(ObjectVisualClass.EditorMarker, objectVisualClass(ObjectType.PlayerSet))
        assertEquals(ObjectVisualClass.InvisibleLogic, objectVisualClass(ObjectType.EventCollision))
        assertEquals(ObjectVisualClass.InvisibleLogic, objectVisualClass(ObjectType.InstaWarp))
        assertEquals(
            ObjectVisualClass.InvisibleLogic,
            objectVisualClass(ObjectType.LobbyGameMenuCollision),
        )
        assertEquals(ObjectVisualClass.InvisibleLogic, objectVisualClass(ObjectType.QuestCollision2))
        assertEquals(ObjectVisualClass.InvisibleLogic, objectVisualClass(ObjectType.Ep4BossRockSpawner))
        assertEquals(ObjectVisualClass.RuntimeVisual, objectVisualClass(ObjectType.Particle))
        assertEquals(ObjectVisualClass.RuntimeVisual, objectVisualClass(ObjectType.AreaWarpEndingJung))
        assertEquals(ObjectVisualClass.RuntimeVisual, objectVisualClass(ObjectType.Seagull))
        assertEquals(ObjectVisualClass.UnavailableModel, objectVisualClass(ObjectType.GBAStation))
        assertEquals(ObjectVisualClass.UnavailableModel, objectVisualClass(ObjectType.EnemyTypeBoxYellow))
        assertEquals(ObjectVisualClass.UnavailableModel, objectVisualClass(ObjectType.DesertPlantHasCollision))
        assertEquals(ObjectVisualClass.UnavailableModel, objectVisualClass(ObjectType.JungleDesign))
        assertEquals(ObjectVisualClass.Unverified, objectVisualClass(ObjectType.UnknownItem832))
    }
}
