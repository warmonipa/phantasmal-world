package world.phantasmal.web.questEditor.loading

import mu.KotlinLogging
import org.khronos.webgl.ArrayBuffer
import world.phantasmal.core.PwResult
import world.phantasmal.core.Success
import world.phantasmal.core.unsafe.UnsafeMap
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.cursor.Cursor
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.ninja.*
import world.phantasmal.psolib.fileFormats.quest.EntityType
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.core.loading.AssetLoader
import world.phantasmal.web.core.loading.LoadingCache
import world.phantasmal.web.core.loading.NPCS_WITH_ULTIMATE_SKIN
import world.phantasmal.web.core.rendering.conversion.ninjaObjectToInstancedMesh
import world.phantasmal.web.core.rendering.disposeObject3DResources
import world.phantasmal.web.externals.three.*
import world.phantasmal.webui.DisposableContainer
import world.phantasmal.webui.obj
import kotlin.js.unsafeCast

private val logger = KotlinLogging.logger {}

/** Supplies independently owned instanced meshes to Quest Editor renderers. */
interface EntityMeshLoader {
    suspend fun loadInstancedMesh(
        type: EntityType,
        model: Int?,
        ultimate: Boolean = false,
        renderVariant: Int? = null,
    ): InstancedMesh
}

class EntityAssetLoader internal constructor(
    private val loadArrayBuffer: suspend (String) -> ArrayBuffer,
) :
    DisposableContainer(),
    EntityMeshLoader {
    constructor(assetLoader: AssetLoader) : this(assetLoader::loadArrayBuffer)

    private val instancedMeshCache = addDisposable(
        LoadingCache<EntityMeshKey, InstancedMesh>(
            { key ->
                loadMesh(key.type, key.model, key.ultimate, key.renderVariant)
                    ?: if (key.type is NpcType) DEFAULT_NPC_MESH else DEFAULT_OBJECT_MESH
            },
            ::disposeObject3DResources
        )
    )

    override suspend fun loadInstancedMesh(
        type: EntityType,
        model: Int?,
        ultimate: Boolean,
        renderVariant: Int?,
    ): InstancedMesh {
        val normalizedVariant = if (type == ObjectType.ForestDoor) {
            forestDoorDigitTextureIndex(renderVariant ?: 0)
        } else {
            null
        }
        return cloneInstancedMeshWithOwnedResources(instancedMeshCache.get(
            EntityMeshKey(type, model, ultimate, normalizedVariant)
        ))
    }

    private suspend fun loadMesh(
        type: EntityType,
        model: Int?,
        ultimate: Boolean,
        renderVariant: Int?,
    ): InstancedMesh? {
        val geomFormat = entityTypeToGeometryFormat(type)

        val geomParts = geometryParts(type).mapNotNull { suffix ->
            loadAssetBuffer(type, AssetType.Geometry, suffix, model, geomFormat, ultimate)
        }

        val ninjaObject = when (geomFormat) {
            GeomFormat.Nj -> parseGeometry(type, geomParts, ::parseNj)
            GeomFormat.Xj -> parseGeometry(type, geomParts, ::parseXj)
            GeomFormat.Rel -> parseGeometry(type, geomParts, ::parseRelNj)
        } ?: return null

        val textures = loadTextures(type, model, ultimate)

        // TODO: Pass anisotropy parameter.
        return ninjaObjectToInstancedMesh(
            ninjaObject,
            textures,
            maxInstances = 300,
            defaultMaterial = MeshLambertMaterial(obj {
                color = if (type is NpcType) DEFAULT_NPC_COLOR else DEFAULT_OBJECT_COLOR
                side = DoubleSide
            }),
            boundingVolumes = true,
            textureIndexOverrides = if (type == ObjectType.ForestDoor) {
                mapOf(FOREST_DOOR_DEFAULT_DIGIT_TEXTURE to (renderVariant ?: 0))
            } else {
                emptyMap()
            },
        ).apply {
            name = type.uniqueName
            // Apply entity-specific scaling
            applyEntityTypeScale(type)
        }
    }

    /**
     * Loads an entity asset buffer. When [ultimate] is set the Ultimate-skin (`.ult`) asset is
     * requested; if that asset is missing the normal-difficulty asset is loaded instead, so the
     * toggle is a no-op for entities without an Ultimate variant.
     */
    private suspend fun loadAssetBuffer(
        type: EntityType,
        assetType: AssetType,
        suffix: String?,
        model: Int?,
        geomFormat: GeomFormat,
        ultimate: Boolean,
    ): Pair<String, ArrayBuffer>? {
        val path = entityTypeToPath(type, assetType, suffix, model, geomFormat, ultimate)
            ?: return null

        return try {
            Pair(path, loadArrayBuffer(path))
        } catch (e: Exception) {
            if (!ultimate) throw e

            val fallback = entityTypeToPath(type, assetType, suffix, model, geomFormat, false)
                ?: return null
            Pair(fallback, loadArrayBuffer(fallback))
        }
    }

    private suspend fun loadTextures(
        type: EntityType,
        model: Int?,
        ultimate: Boolean,
    ): List<XvrTexture> {
        val suffix =
            if (
                type === ObjectType.FloatingRocks ||
                type === ObjectType.BigBrownRock
            ) {
                "-${model ?: 0}"
            } else {
                ""
            }

        // GeomFormat is irrelevant for textures.
        val (path, buffer) =
            loadAssetBuffer(type, AssetType.Texture, suffix, model, GeomFormat.Nj, ultimate)
                ?: return emptyList()

        val xvm = parseXvm(buffer.cursor(endianness = Endianness.Little))

        return if (xvm is Success) {
            xvm.value.textures
        } else {
            logger.warn { "Couldn't parse $path for $type." }
            emptyList()
        }
    }

    private fun <Obj : NinjaObject<*, Obj>> parseGeometry(
        type: EntityType,
        parts: List<Pair<String, ArrayBuffer>>,
        parse: (Cursor) -> PwResult<List<Obj>>,
    ): Obj? {
        val ninjaObjects = parts.flatMap { (path, data) ->
            val njObjects = parse(data.cursor(Endianness.Little))

            if (njObjects is Success && njObjects.value.isNotEmpty()) {
                njObjects.value
            } else {
                logger.warn { "Couldn't parse $path for $type." }
                emptyList()
            }
        }

        if (ninjaObjects.isEmpty()) {
            return null
        }

        val ninjaObject = ninjaObjects.first()
        ninjaObject.evaluationFlags.breakChildTrace = false

        for (njObj in ninjaObjects.drop(1)) {
            ninjaObject.addChild(njObj)
        }

        return ninjaObject
    }

    companion object {
        private val DEFAULT_NPC_COLOR = Color(0xFF0000)
        private val DEFAULT_OBJECT_COLOR = Color(0xFFFF00)
        private const val FOREST_DOOR_DEFAULT_DIGIT_TEXTURE = 0

        private val DEFAULT_NPC_MESH = createCylinder(DEFAULT_NPC_COLOR)
        private val DEFAULT_OBJECT_MESH = createCylinder(DEFAULT_OBJECT_COLOR)

        private fun createCylinder(color: Color) =
            InstancedMesh(
                CylinderGeometry(
                    radiusTop = 2.5,
                    radiusBottom = 2.5,
                    height = 18.0,
                    radialSegments = 20,
                ).apply {
                    translate(0.0, 9.0, 0.0)
                    computeBoundingBox()
                    computeBoundingSphere()
                },
                MeshLambertMaterial(obj { this.color = color }),
                count = 1000,
            ).apply {
                // Start with 0 instances.
                count = 0
            }
    }
}

/**
 * Three.js object cloning shares geometry, materials, and textures. Renderer consumers dispose
 * those resources, so clone every disposable resource while still sharing immutable source data
 * (such as a texture's image) with the cached prototype.
 */
internal fun cloneInstancedMeshWithOwnedResources(source: InstancedMesh): InstancedMesh {
    val clone = source.clone().unsafeCast<InstancedMesh>()
    val clonedTextures = UnsafeMap<Texture, Texture>()
    clone.geometry = source.geometry.clone()
    clone.material = if (source.material is Array<*>) {
        source.material.unsafeCast<Array<Material>>()
            .map { cloneMaterialWithOwnedTexture(it, clonedTextures) }
            .toTypedArray()
    } else {
        cloneMaterialWithOwnedTexture(source.material.unsafeCast<Material>(), clonedTextures)
    }
    return clone
}

private fun cloneMaterialWithOwnedTexture(
    source: Material,
    clonedTextures: UnsafeMap<Texture, Texture>,
): Material {
    val clone = source.asDynamic().clone().unsafeCast<Material>()
    val sourceMap = source.asDynamic().map?.unsafeCast<Texture>()
    if (sourceMap != null) {
        val clonedMap = clonedTextures.get(sourceMap)
            ?: sourceMap.asDynamic().clone().unsafeCast<Texture>().also {
                // Texture.copy(), which Three.js clone() uses, does not copy the upload version. The
                // cached prototype is never rendered, so explicitly schedule this independently
                // owned texture for its first GPU upload.
                it.needsUpdate = true
                clonedTextures.set(sourceMap, it)
            }
        clone.asDynamic().map = clonedMap
    }
    return clone
}

/**
 * Apply entity-specific scaling. Clones the geometry first to avoid mutating shared cached geometry.
 */
private fun InstancedMesh.applyEntityTypeScale(type: EntityType) {
    val scaleFactor = when (type) {
        NpcType.Delbiter -> 0.5
        else -> return
    }

    geometry = geometry.clone().apply {
        scale(scaleFactor, scaleFactor, scaleFactor)
        computeBoundingBox()
        computeBoundingSphere()
    }
}

/**
 * The first ten textures in 128.xvm match the PSO world-font digit order 0-9.
 */
internal fun forestDoorDigitTextureIndex(digit: Int): Int = digit.mod(10)

private data class EntityMeshKey(
    val type: EntityType,
    val model: Int?,
    val ultimate: Boolean,
    val renderVariant: Int?,
)

private enum class AssetType {
    Geometry, Texture
}

private enum class GeomFormat {
    Nj, Xj, Rel
}

/**
 * NPC types that have a distinct Ultimate-difficulty skin. Episode II "2" / omnispawn siblings
 * redirect to their stock type in [entityTypeToPath], so they inherit the Ultimate skin
 * automatically. The set itself lives in [NPCS_WITH_ULTIMATE_SKIN] so the viewer shares it.
 */
private val ULTIMATE_NPCS: Set<NpcType> = NPCS_WITH_ULTIMATE_SKIN

/** Object types that have a distinct Ultimate-difficulty skin (`<id>.ult.*`). */
private val ULTIMATE_OBJECTS: Set<ObjectType> = emptySet()

/**
 * Returns the suffix of each geometry part.
 */
private fun geometryParts(type: EntityType): List<String?> =
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
        ObjectType.WelcomeBoard -> listOf("") // TODO: position part 2 correctly.
        ObjectType.ForestDoor -> listOf("", "-2", "-3", "-4", "-5")
        ObjectType.ForestSwitch -> listOf("", "-2", "-3")
        ObjectType.LaserFence -> listOf("", "-2")
        ObjectType.LaserSquareFence -> listOf("", "-2")
        ObjectType.ForestLaserFenceSwitch -> listOf("", "-2", "-3")
        ObjectType.Probe -> listOf("-0") // TODO: use correct part.
        ObjectType.RandomTypeBox1 -> listOf("-2") // What are the other two parts for?
        ObjectType.BlackSlidingDoor -> listOf("", "-2")
        ObjectType.EnergyBarrier -> listOf("", "-2")
        ObjectType.SwitchNoneDoor -> listOf("", "-2")
        ObjectType.EnemyBoxGrey -> listOf("-2") // What are the other two parts for?
        ObjectType.FixedTypeBox -> listOf("-3") // What are the other three parts for?
        ObjectType.EnemyBoxBrown -> listOf("-3") // What are the other three parts for?
        ObjectType.LaserFenceEx -> listOf("", "-2")
        ObjectType.LaserSquareFenceEx -> listOf("", "-2")
        ObjectType.CavesSmashingPillar -> listOf("", "-3") // What's part 2 for?
        ObjectType.RobotRechargeStation -> listOf("", "-2")
        ObjectType.RuinsTeleporter -> listOf("", "-2", "-3", "-4")
        ObjectType.RuinsWarpSiteToSite -> listOf("", "-2")
        ObjectType.RuinsSwitch -> listOf("", "-2")
        ObjectType.RuinsPillarTrap -> listOf("", "-2", "-3", "-4")
        ObjectType.RuinsCrystal -> listOf("", "-2", "-3")
        ObjectType.FloatingRocks -> listOf("-0")
        ObjectType.ItemBoxCca -> listOf("", "-3") // What are the other two parts for?
        ObjectType.TeleporterEp2 -> listOf("", "-2")
        ObjectType.CcaDoor -> listOf("", "-2")
        ObjectType.SpecialBoxCca -> listOf("", "-4") // What are the other two parts for?
        ObjectType.BigCcaDoor -> listOf("", "-2", "-3", "-4")
        ObjectType.BigCcaDoorSwitch -> listOf("", "-2")
        ObjectType.LaserDetect -> listOf("", "-2") // TODO: use correct part.
        ObjectType.LabCeilingWarp -> listOf("", "-2")
        ObjectType.BigBrownRock -> listOf("-0") // TODO: use correct part.
        ObjectType.BigBlackRocks -> listOf("")
        ObjectType.BeeHive -> listOf("", "-0", "-1")
        ObjectType.ForestConsole -> listOf("") // All model variants share the same geometry.
        else -> listOf(null)
    }

private fun entityTypeToGeometryFormat(type: EntityType): GeomFormat =
    when (type) {
        is NpcType -> {
            when (type) {
                NpcType.Dubswitch,
                NpcType.Dubswitch2,
                NpcType.VolOptMonitor,
                -> GeomFormat.Xj

                // Player class NPCs use .rel format
                NpcType.NpcHUmar,
                NpcType.NpcHUnewearl,
                NpcType.NpcRAmar,
                NpcType.NpcRAcast,
                NpcType.NpcRAcaseal,
                NpcType.NpcFOmarl,
                NpcType.NpcFOnewm,
                NpcType.NpcFOnewearl,
                NpcType.NpcHUnewearl2,
                NpcType.NpcHUcast,
                NpcType.NpcRAmar2,
                NpcType.NpcFOmarl2,
                NpcType.NpcFOnewm2,
                NpcType.NpcFOnewearl2,
                NpcType.Rupika, // Redirects to NpcFOnewearl (.rel)
                -> GeomFormat.Rel

                else -> GeomFormat.Nj
            }
        }

        is ObjectType -> {
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
                -> GeomFormat.Nj

                else -> GeomFormat.Xj
            }
        }

        else -> {
            error("$type not supported.")
        }
    }

private fun entityTypeToPath(
    type: EntityType,
    assetType: AssetType,
    suffix: String?,
    model: Int?,
    geomFormat: GeomFormat,
    ultimate: Boolean,
): String? {
    val fullSuffix = when {
        suffix != null -> suffix
        model != null -> "-$model"
        else -> ""
    }

    val extension = when (assetType) {
        AssetType.Geometry -> when (geomFormat) {
            GeomFormat.Nj -> "nj"
            GeomFormat.Xj -> "xj"
            GeomFormat.Rel -> "rel"
        }

        AssetType.Texture -> "xvm"
    }

    return when (type) {
        is NpcType -> {
            when (type) {
                // We don't have a model for these NPCs.
                NpcType.Unknown,
                NpcType.NpcEnemy,
                -> null

                // Rupika is FOnewearl, share her model.
                NpcType.Rupika ->
                    entityTypeToPath(NpcType.NpcFOnewearl, assetType, suffix, model, geomFormat, ultimate)

                // Friendly NPC versions of enemies share enemy models.
                NpcType.NpcLappy ->
                    entityTypeToPath(NpcType.RagRappy, assetType, suffix, model, geomFormat, ultimate)

                NpcType.NpcMoja ->
                    entityTypeToPath(NpcType.Hildebear, assetType, suffix, model, geomFormat, ultimate)

                NpcType.NpcBringer ->
                    entityTypeToPath(NpcType.ChaosBringer, assetType, suffix, model, geomFormat, ultimate)

                // Episode II VR Temple

                NpcType.Hildebear2 ->
                    entityTypeToPath(NpcType.Hildebear, assetType, suffix, model, geomFormat, ultimate)

                NpcType.Hildeblue2 ->
                    entityTypeToPath(NpcType.Hildeblue, assetType, suffix, model, geomFormat, ultimate)

                NpcType.RagRappy2 ->
                    entityTypeToPath(NpcType.RagRappy, assetType, suffix, model, geomFormat, ultimate)

                NpcType.Monest2 ->
                    entityTypeToPath(NpcType.Monest, assetType, suffix, model, geomFormat, ultimate)

                NpcType.Mothmant2 ->
                    entityTypeToPath(NpcType.Mothmant, assetType, suffix, model, geomFormat, ultimate)

                NpcType.PoisonLily2 ->
                    entityTypeToPath(NpcType.PoisonLily, assetType, suffix, model, geomFormat, ultimate)

                NpcType.NarLily2 ->
                    entityTypeToPath(NpcType.NarLily, assetType, suffix, model, geomFormat, ultimate)

                // Omnispawn pseudo-types share their stock sibling's model.
                NpcType.PoisonLilyOmni ->
                    entityTypeToPath(NpcType.PoisonLily, assetType, suffix, model, geomFormat, ultimate)

                NpcType.DelLilyOmni ->
                    entityTypeToPath(NpcType.DelLily, assetType, suffix, model, geomFormat, ultimate)

                NpcType.EpsilonOmni ->
                    entityTypeToPath(NpcType.Epsilon, assetType, suffix, model, geomFormat, ultimate)

                NpcType.SinowZoaOmni ->
                    entityTypeToPath(NpcType.SinowZoa, assetType, suffix, model, geomFormat, ultimate)

                NpcType.GrassAssassin2 ->
                    entityTypeToPath(NpcType.GrassAssassin, assetType, suffix, model, geomFormat, ultimate)

                NpcType.Dimenian2 ->
                    entityTypeToPath(NpcType.Dimenian, assetType, suffix, model, geomFormat, ultimate)

                NpcType.LaDimenian2 ->
                    entityTypeToPath(NpcType.LaDimenian, assetType, suffix, model, geomFormat, ultimate)

                NpcType.SoDimenian2 ->
                    entityTypeToPath(NpcType.SoDimenian, assetType, suffix, model, geomFormat, ultimate)

                NpcType.DarkBelra2 ->
                    entityTypeToPath(NpcType.DarkBelra, assetType, suffix, model, geomFormat, ultimate)

                // Episode II VR Spaceship

                NpcType.SavageWolf2 ->
                    entityTypeToPath(NpcType.SavageWolf, assetType, suffix, model, geomFormat, ultimate)

                NpcType.BarbarousWolf2 ->
                    entityTypeToPath(NpcType.BarbarousWolf, assetType, suffix, model, geomFormat, ultimate)

                NpcType.PanArms2 ->
                    entityTypeToPath(NpcType.PanArms, assetType, suffix, model, geomFormat, ultimate)

                NpcType.Migium2 ->
                    entityTypeToPath(NpcType.Migium, assetType, suffix, model, geomFormat, ultimate)

                NpcType.Hidoom2 ->
                    entityTypeToPath(NpcType.Hidoom, assetType, suffix, model, geomFormat, ultimate)

                NpcType.Dubchic2 ->
                    entityTypeToPath(NpcType.Dubchic, assetType, suffix, model, geomFormat, ultimate)

                NpcType.Gilchic2 ->
                    entityTypeToPath(NpcType.Gilchic, assetType, suffix, model, geomFormat, ultimate)

                NpcType.Garanz2 ->
                    entityTypeToPath(NpcType.Garanz, assetType, suffix, model, geomFormat, ultimate)

                NpcType.Dubswitch2 ->
                    entityTypeToPath(NpcType.Dubswitch, assetType, suffix, model, geomFormat, ultimate)

                NpcType.Delsaber2 ->
                    entityTypeToPath(NpcType.Delsaber, assetType, suffix, model, geomFormat, ultimate)

                NpcType.ChaosSorcerer2 ->
                    entityTypeToPath(NpcType.ChaosSorcerer, assetType, suffix, model, geomFormat, ultimate)

                else -> {
                    val ult = if (ultimate && type in ULTIMATE_NPCS) ".ult" else ""
                    "/npcs/${type.name}${fullSuffix}${ult}.$extension"
                }
            }
        }

        is ObjectType -> {
            when (type) {
                // We don't have a model for these objects.
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
                -> null

                else -> {
                    type.typeId?.let { typeId ->
                        val ult = if (ultimate && type in ULTIMATE_OBJECTS) ".ult" else ""
                        "/objects/${typeId}${fullSuffix}${ult}.$extension"
                    }
                }
            }
        }

        else -> {
            error("$type not supported.")
        }
    }
}
