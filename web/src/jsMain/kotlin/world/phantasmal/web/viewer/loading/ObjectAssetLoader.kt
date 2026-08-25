package world.phantasmal.web.viewer.loading

import mu.KotlinLogging
import org.khronos.webgl.ArrayBuffer
import world.phantasmal.core.PwResult
import world.phantasmal.core.Success
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.cursor.Cursor
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.ninja.NinjaObject
import world.phantasmal.psolib.fileFormats.ninja.XvrTexture
import world.phantasmal.psolib.fileFormats.ninja.parseNj
import world.phantasmal.psolib.fileFormats.ninja.parseXj
import world.phantasmal.psolib.fileFormats.ninja.parseXvm
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.core.loading.AssetLoader
import world.phantasmal.web.core.loading.LoadingCache
import world.phantasmal.webui.DisposableContainer

private val logger = KotlinLogging.logger {}

class ObjectAssetLoader(private val assetLoader: AssetLoader) : DisposableContainer() {
    private val ninjaObjectCache: LoadingCache<ObjectType, NinjaObject<*, *>> =
        addDisposable(LoadingCache(::loadGeometry) { /* Nothing to dispose. */ })

    private val xvrTextureCache: LoadingCache<ObjectType, List<XvrTexture>> =
        addDisposable(LoadingCache(::loadTextures) { /* Nothing to dispose. */ })

    suspend fun loadNinjaObject(objectType: ObjectType): NinjaObject<*, *> =
        ninjaObjectCache.get(objectType)

    suspend fun loadXvrTextures(objectType: ObjectType): List<XvrTexture> =
        xvrTextureCache.get(objectType)

    private suspend fun loadGeometry(objectType: ObjectType): NinjaObject<*, *> {
        val geomFormat = objectTypeToGeometryFormat(objectType)
        val geomParts = objectGeometryParts(objectType).mapNotNull { suffix ->
            objectTypeToPath(objectType, suffix, geomFormat)?.let { path ->
                try {
                    Pair(path, assetLoader.loadArrayBuffer(path))
                } catch (_: Exception) {
                    null
                }
            }
        }

        val ninjaObject = when (geomFormat) {
            ObjectGeomFormat.Nj -> parseGeometry(objectType, geomParts, ::parseNj)
            ObjectGeomFormat.Xj -> parseGeometry(objectType, geomParts, ::parseXj)
        }

        return ninjaObject ?: error("Failed to load object geometry for ${objectType.uniqueName}.")
    }

    private suspend fun loadTextures(objectType: ObjectType): List<XvrTexture> {
        val suffix =
            if (
                objectType === ObjectType.FloatingRocks ||
                objectType === ObjectType.BigBrownRock
            ) {
                "-0"
            } else {
                ""
            }

        val path = objectType.typeId?.let { "/objects/$it$suffix.xvm" } ?: return emptyList()

        val buffer = try {
            assetLoader.loadArrayBuffer(path)
        } catch (_: Exception) {
            return emptyList()
        }

        val xvm = parseXvm(buffer.cursor(Endianness.Little))
        return if (xvm is Success) {
            xvm.value.textures
        } else {
            logger.warn { "Couldn't parse $path for ${objectType.uniqueName}." }
            emptyList()
        }
    }

    private fun <Obj : NinjaObject<*, Obj>> parseGeometry(
        type: ObjectType,
        parts: List<Pair<String, ArrayBuffer>>,
        parse: (Cursor) -> PwResult<List<Obj>>,
    ): Obj? {
        val ninjaObjects = parts.flatMap { (path, data) ->
            val objects = parse(data.cursor(Endianness.Little))

            if (objects is Success && objects.value.isNotEmpty()) {
                objects.value
            } else {
                logger.warn { "Couldn't parse $path for ${type.uniqueName}." }
                emptyList()
            }
        }

        if (ninjaObjects.isEmpty()) {
            return null
        }

        val ninjaObject = ninjaObjects.first()
        ninjaObject.evaluationFlags.breakChildTrace = false

        for (obj in ninjaObjects.drop(1)) {
            ninjaObject.addChild(obj)
        }

        return ninjaObject
    }
}

private enum class ObjectGeomFormat {
    Nj,
    Xj,
}

private fun objectGeometryParts(type: ObjectType): List<String?> =
    when (type) {
        ObjectType.Teleporter -> listOf("", "-2")
        ObjectType.Warp -> listOf("", "-2")
        ObjectType.BossTeleporter -> listOf("", "-2")
        ObjectType.QuestWarp -> listOf("", "-2")
        ObjectType.Epilogue -> listOf("", "-2")
        ObjectType.MainRagolTeleporter -> listOf("", "-2")
        ObjectType.PrincipalWarp -> listOf("", "-2")
        ObjectType.TeleporterDoor -> listOf("", "-2")
        ObjectType.EasterEgg -> listOf("", "-2")
        ObjectType.ValentinesHeart -> listOf("", "-2", "-3")
        ObjectType.ChristmasTree -> listOf("", "-2", "-3", "-4")
        ObjectType.TwentyFirstCentury -> listOf("", "-2")
        ObjectType.WelcomeBoard -> listOf("")
        ObjectType.ForestDoor -> listOf("", "-2", "-3", "-4", "-5")
        ObjectType.ForestSwitch -> listOf("", "-2", "-3")
        ObjectType.LaserFence -> listOf("", "-2")
        ObjectType.LaserSquareFence -> listOf("", "-2")
        ObjectType.ForestLaserFenceSwitch -> listOf("", "-2", "-3")
        ObjectType.Probe -> listOf("-0")
        ObjectType.RandomTypeBox1 -> listOf("-2")
        ObjectType.BlackSlidingDoor -> listOf("", "-2")
        ObjectType.EnergyBarrier -> listOf("", "-2")
        ObjectType.SwitchNoneDoor -> listOf("", "-2")
        ObjectType.EnemyBoxGrey -> listOf("-2")
        ObjectType.FixedTypeBox -> listOf("-3")
        ObjectType.EnemyBoxBrown -> listOf("-3")
        ObjectType.LaserFenceEx -> listOf("", "-2")
        ObjectType.LaserSquareFenceEx -> listOf("", "-2")
        ObjectType.CavesSmashingPillar -> listOf("", "-3")
        ObjectType.RobotRechargeStation -> listOf("", "-2")
        ObjectType.RuinsTeleporter -> listOf("", "-2", "-3", "-4")
        ObjectType.RuinsWarpSiteToSite -> listOf("", "-2")
        ObjectType.RuinsSwitch -> listOf("", "-2")
        ObjectType.RuinsPillarTrap -> listOf("", "-2", "-3", "-4")
        ObjectType.RuinsCrystal -> listOf("", "-2", "-3")
        ObjectType.FloatingRocks -> listOf("-0")
        ObjectType.ItemBoxCca -> listOf("", "-3")
        ObjectType.TeleporterEp2 -> listOf("", "-2")
        ObjectType.CcaDoor -> listOf("", "-2")
        ObjectType.SpecialBoxCca -> listOf("", "-4")
        ObjectType.BigCcaDoor -> listOf("", "-2", "-3", "-4")
        ObjectType.BigCcaDoorSwitch -> listOf("", "-2")
        ObjectType.LaserDetect -> listOf("", "-2")
        ObjectType.LabMapWarp -> listOf("", "-2")
        ObjectType.BigBrownRock -> listOf("-0")
        ObjectType.BigBlackRocks -> listOf("")
        ObjectType.BeeHive -> listOf("", "-0", "-1")
        ObjectType.ForestConsole -> listOf("")
        else -> listOf(null)
    }

private fun objectTypeToGeometryFormat(type: ObjectType): ObjectGeomFormat =
    when (type) {
        ObjectType.EasterEgg,
        ObjectType.ChristmasTree,
        ObjectType.ChristmasWreath,
        ObjectType.TwentyFirstCentury,
        ObjectType.Sonic,
        ObjectType.WelcomeBoard,
        ObjectType.FloatingJellyfish,
        ObjectType.RuinsSeal,
        ObjectType.Dolphin,
        ObjectType.Cactus,
        ObjectType.BigBrownRock,
        ObjectType.PoisonPlant,
        ObjectType.BigBlackRocks,
        ObjectType.FallingRock,
        ObjectType.DesertFixedTypeBoxBreakableCrystals,
        ObjectType.BeeHive,
        ObjectType.LobbyPigeon,
        ObjectType.ContainerJungEnemy,
        -> ObjectGeomFormat.Nj

        else -> ObjectGeomFormat.Xj
    }

private fun objectTypeToPath(
    type: ObjectType,
    suffix: String?,
    geomFormat: ObjectGeomFormat,
): String? {
    val typeId = type.typeId ?: return null
    val extension = when (geomFormat) {
        ObjectGeomFormat.Nj -> "nj"
        ObjectGeomFormat.Xj -> "xj"
    }
    return "/objects/$typeId${suffix ?: ""}.$extension"
}
