package world.phantasmal.web.core.models

import world.phantasmal.psolib.fileFormats.quest.ObjectType

/** Why the editor does or does not load a static model for an object type. */
internal enum class ObjectVisualClass {
    StaticModel,
    EditorMarker,
    InvisibleLogic,
    RuntimeVisual,
    UnavailableModel,
    Unverified,
}

internal fun objectVisualClass(type: ObjectType): ObjectVisualClass =
    when (type) {
        ObjectType.PlayerSet -> ObjectVisualClass.EditorMarker

        ObjectType.LightCollision,
        ObjectType.EnvSound,
        ObjectType.FogCollision,
        ObjectType.EventCollision,
        ObjectType.CharaCollision,
        ObjectType.ObjRoomID,
        ObjectType.ScriptCollision,
        ObjectType.MapCollision,
        ObjectType.ScriptCollisionA,
        ObjectType.RadarCollision,
        ObjectType.FogCollisionSW,
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
        ObjectType.Camera,
        ObjectType.LightningController,
        ObjectType.LiveCamera,
        ObjectType.InstaWarp,
        ObjectType.QuestCollision2,
        ObjectType.Poison,
        ObjectType.Heat,
        ObjectType.Ep4BossRockSpawner,
        ObjectType.LobbyGameMenuCollision,
        -> ObjectVisualClass.InvisibleLogic

        ObjectType.Particle,
        ObjectType.LensFlare,
        ObjectType.ItemLight,
        ObjectType.ImageBoard,
        ObjectType.StarLight2D,
        ObjectType.LensFlare2,
        ObjectType.Firework,
        ObjectType.Rainbow,
        ObjectType.FloatingBlueLight,
        ObjectType.FloatingSoul,
        ObjectType.Butterfly,
        ObjectType.CcaAreaTeleporter,
        ObjectType.WhiteBird,
        ObjectType.OrangeBird,
        ObjectType.BiwaMushi,
        ObjectType.Seagull,
        ObjectType.Ep2Particle,
        ObjectType.WarpInBarbaRayRoom,
        ObjectType.AreaWarpEndingJung,
        ObjectType.Ep4LightSource,
        ObjectType.Ep4TestParticle,
        -> ObjectVisualClass.RuntimeVisual

        ObjectType.MainRagolTeleporterBattleInNextArea,
        ObjectType.PopupTrapNoTech,
        ObjectType.EnemyTypeBoxYellow,
        ObjectType.EnemyTypeBoxBlue,
        ObjectType.EmptyTypeBoxBlue,
        ObjectType.BreakableBrownRock,
        ObjectType.OozingDesertPlant,
        ObjectType.DesertPlantHasCollision,
        ObjectType.Ep4TestDoor,
        ObjectType.TopOfSaintMillionEgg,
        ObjectType.GBAStation,
        ObjectType.JungleDesign,
        -> ObjectVisualClass.UnavailableModel

        ObjectType.Unknown,
        ObjectType.UnknownItem16,
        ObjectType.UnknownItem832,
        ObjectType.UnknownItem833,
        ObjectType.UnknownItem897,
        ObjectType.UnknownItem898,
        ObjectType.UnknownItem901,
        ObjectType.UnknownItem903,
        ObjectType.UnknownItem904,
        ObjectType.UnknownItem905,
        ObjectType.UnknownItem906,
        ObjectType.Battery,
        -> ObjectVisualClass.Unverified

        else -> ObjectVisualClass.StaticModel
    }
