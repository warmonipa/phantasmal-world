package world.phantasmal.web.viewer.models

import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.ObjectType

sealed class ViewerModel {
    abstract val uiName: String
    abstract val slug: String

    data class Character(val characterClass: CharacterClass) : ViewerModel() {
        override val uiName: String = characterClass.uiName
        override val slug: String = characterClass.slug
    }

    data class Npc(val npcType: NpcType) : ViewerModel() {
        override val uiName: String = npcType.uniqueName
        override val slug: String = npcType.name
    }

    data class Object(val objectType: ObjectType) : ViewerModel() {
        override val uiName: String = objectType.uniqueName
        override val slug: String = "Object_${objectType.name}"
    }

    data class Group(val label: String, val items: List<ViewerModel>)

    companion object {
        val CHARACTERS: List<ViewerModel> = CharacterClass.VALUES_LIST.map(::Character)

        val EP1_ENEMIES: List<ViewerModel> = listOf(
            NpcType.Hildebear,
            NpcType.Hildeblue,
            NpcType.RagRappy,
            NpcType.AlRappy,
            NpcType.Monest,
            NpcType.Mothmant,
            NpcType.SavageWolf,
            NpcType.BarbarousWolf,
            NpcType.Booma,
            NpcType.Gobooma,
            NpcType.Gigobooma,
            NpcType.GrassAssassin,
            NpcType.PoisonLily,
            NpcType.NarLily,
            NpcType.EvilShark,
            NpcType.PalShark,
            NpcType.GuilShark,
            NpcType.PanArms,
            NpcType.Dubchic,
            NpcType.Gilchic,
            NpcType.Garanz,
            NpcType.SinowBeat,
            NpcType.SinowGold,
            NpcType.Canadine,
            NpcType.Canane,
            NpcType.Delsaber,
            NpcType.ChaosSorcerer,
            NpcType.DarkGunner,
            NpcType.DarkBelra,
            NpcType.Bulclaw,
            NpcType.Claw,
            NpcType.Dimenian,
            NpcType.LaDimenian,
            NpcType.SoDimenian,
            NpcType.ChaosBringer,
            NpcType.PofuillySlime,
            NpcType.PouillySlime,
        ).map(::Npc)

        val EP2_ENEMIES: List<ViewerModel> = listOf(
            NpcType.Merillia,
            NpcType.Meriltas,
            NpcType.Mericarol,
            NpcType.Merikle,
            NpcType.Mericus,
            NpcType.UlGibbon,
            NpcType.ZolGibbon,
            NpcType.Gibbles,
            NpcType.Gee,
            NpcType.GiGue,
            NpcType.IllGill,
            NpcType.DelLily,
            NpcType.Epsilon,
            NpcType.SinowBerill,
            NpcType.SinowSpigell,
            NpcType.Dolmolm,
            NpcType.Dolmdarl,
            NpcType.Morfos,
            NpcType.Recobox,
            NpcType.Delbiter,
            NpcType.SinowZoa,
            NpcType.SinowZele,
            NpcType.Deldepth,
        ).map(::Npc)

        val EP4_ENEMIES: List<ViewerModel> = listOf(
            NpcType.Boota,
            NpcType.ZeBoota,
            NpcType.BaBoota,
            NpcType.Dorphon,
            NpcType.DorphonEclair,
            NpcType.Goran,
            NpcType.PyroGoran,
            NpcType.GoranDetonator,
            NpcType.SandRappy,
            NpcType.DelRappy,
            NpcType.Astark,
            NpcType.SatelliteLizard,
            NpcType.Yowie,
            NpcType.MerissaA,
            NpcType.MerissaAA,
            NpcType.Girtablulu,
            NpcType.Zu,
            NpcType.Pazuzu,
        ).map(::Npc)

        val BOSSES: List<ViewerModel> = listOf(
            // EP1
            NpcType.Dragon,
            NpcType.DeRolLe,
            NpcType.VolOptPart1,
            NpcType.VolOptPart2,
            NpcType.DarkFalz,
            // EP2
            NpcType.BarbaRay,
            NpcType.GalGryphon,
            NpcType.OlgaFlow,
            // EP4
            NpcType.SaintMilion,
            NpcType.Shambertin,
            NpcType.Kondrieu,
            NpcType.GolDragon,
        ).map(::Npc)

        private val OBJECTS_WITHOUT_VIEWER_ASSET: Set<ObjectType> = setOf(
            ObjectType.Unknown,
            ObjectType.PlayerSet,
            ObjectType.Particle,
            ObjectType.LightCollision,
            ObjectType.EnvSound,
            ObjectType.FogCollision,
            ObjectType.EventCollision,
            ObjectType.CharaCollision,
            ObjectType.ObjRoomID,
            ObjectType.LensFlare,
            ObjectType.ScriptCollision,
            ObjectType.MapCollision,
            ObjectType.ScriptCollisionA,
            ObjectType.ItemLight,
            ObjectType.RadarCollision,
            ObjectType.FogCollisionSW,
            ObjectType.ImageBoard,
            ObjectType.StarLight2D,
            ObjectType.LensFlare2,
            ObjectType.RadarHideCollision,
            ObjectType.MenuActivation,
            ObjectType.BoxDetectObject,
            ObjectType.SymbolChatObject,
            ObjectType.TouchPlateObject,
            ObjectType.TargetableObject,
            ObjectType.EffectObject,
            ObjectType.CountDownObject,
            ObjectType.ChatSensor,
            ObjectType.RadarIcon,
            ObjectType.EnvSoundEx,
            ObjectType.EnvSoundGlobal,
            ObjectType.TelepipeLocation,
            ObjectType.BGMCollision,
            ObjectType.Pioneer2InvisibleTouchplate,
            ObjectType.TempleMapDetect,
            ObjectType.Firework,
            ObjectType.MainRagolTeleporterBattleInNextArea,
            ObjectType.Rainbow,
            ObjectType.FloatingBlueLight,
            ObjectType.PopupTrapNoTech,
            ObjectType.Poison,
            ObjectType.EnemyTypeBoxYellow,
            ObjectType.EnemyTypeBoxBlue,
            ObjectType.EmptyTypeBoxBlue,
            ObjectType.FloatingSoul,
            ObjectType.Butterfly,
            ObjectType.Camera,
            ObjectType.CcaAreaTeleporter,
            ObjectType.LightningController,
            ObjectType.WhiteBird,
            ObjectType.OrangeBird,
            ObjectType.BiwaMushi,
            ObjectType.JungleDesign,
            ObjectType.Seagull,
            ObjectType.Ep2Particle,
            ObjectType.WarpInBarbaRayRoom,
            ObjectType.LiveCamera,
            ObjectType.InstaWarp,
            ObjectType.LabInvisibleObject,
            ObjectType.AreaWarpEndingJung,
            ObjectType.Ep4LightSource,
            ObjectType.BreakableBrownRock,
            ObjectType.UnknownItem897,
            ObjectType.UnknownItem898,
            ObjectType.OozingDesertPlant,
            ObjectType.UnknownItem901,
            ObjectType.UnknownItem903,
            ObjectType.UnknownItem904,
            ObjectType.UnknownItem905,
            ObjectType.UnknownItem906,
            ObjectType.DesertPlantHasCollision,
            ObjectType.Ep4TestDoor,
            ObjectType.Ep4TestParticle,
            ObjectType.Heat,
            ObjectType.TopOfSaintMillionEgg,
            ObjectType.Ep4BossRockSpawner,
            ObjectType.UnknownItem16,
            ObjectType.Battery,
            ObjectType.LobbyGameMenu,
            ObjectType.GBAStation,
            ObjectType.UnknownItem832,
            ObjectType.UnknownItem833,
        )

        val OBJECTS: List<ViewerModel> = ObjectType.entries
            .filter(::objectTypeHasViewerAsset)
            .map(::Object)

        val OBJECT_GROUPS: List<Group> = OBJECTS
            .filterIsInstance<Object>()
            .groupBy { objectGroupLabel(it.objectType) }
            .map { (label, items) -> Group(label, items.sortedBy { it.objectType.typeId }) }

        val ALL: List<ViewerModel> =
            CHARACTERS + EP1_ENEMIES + EP2_ENEMIES + EP4_ENEMIES + BOSSES + OBJECTS

        val GROUPS: List<Group> = listOf(
            Group("Characters", CHARACTERS),
            Group("EP1 Enemies", EP1_ENEMIES),
            Group("EP2 Enemies", EP2_ENEMIES),
            Group("EP4 Enemies", EP4_ENEMIES),
            Group("Bosses", BOSSES),
        ) + OBJECT_GROUPS

        fun findBySlug(slug: String): ViewerModel? = ALL.find { it.slug == slug }

        private fun objectGroupLabel(type: ObjectType): String {
            val typeId = type.typeId?.toInt() ?: return "Objects - Other"
            return when (typeId) {
                in 0..63 -> "Objects - Common"
                in 64..87 -> "Objects - Pioneer 2"
                in 128..151 -> "Objects - Forest"
                in 192..225 -> "Objects - Caves"
                in 256..268 -> "Objects - Mines"
                in 304..372 -> "Objects - Ruins"
                in 384..396 -> "Objects - Lobby"
                in 400..403, 448 -> "Objects - Spaceship"
                in 416..427 -> "Objects - Temple"
                in 512..531 -> "Objects - CCA"
                in 544..553 -> "Objects - Seabed"
                in 576..701 -> "Objects - Episode II"
                in 768..961 -> "Objects - Episode IV"
                else -> "Objects - Other"
            }
        }

        private fun objectTypeHasViewerAsset(type: ObjectType): Boolean =
            type.typeId != null && type !in OBJECTS_WITHOUT_VIEWER_ASSET
    }
}
