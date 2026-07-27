package world.phantasmal.web.questEditor.rendering

import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import mu.KotlinLogging
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawn
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawnOrigin
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawnSource
import world.phantasmal.psolib.fileFormats.particle.GLOBAL_PARTICLE_EFFECT_COUNT
import world.phantasmal.psolib.fileFormats.particle.ParticleEffectData
import world.phantasmal.web.externals.three.*
import world.phantasmal.web.questEditor.loading.ParticleAssetLoader
import world.phantasmal.web.questEditor.loading.ParticleAssets
import world.phantasmal.web.questEditor.loading.ParticleTexture
import world.phantasmal.webui.DisposableContainer
import world.phantasmal.webui.obj
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private val logger = KotlinLogging.logger {}
private const val MIN_PARTICLE_PREVIEW_SIZE = 32.0

/**
 * Previews quest-created PSOBB particle emitters with the client's particleentry templates and
 * effect_nt textures. The editor loops finite-duration emitters so their appearance remains
 * inspectable. Yellow cylinders are retained only when the template or texture cannot be loaded.
 */
class ParticleMarkerManager internal constructor(
    private val renderContext: QuestRenderContext,
    private val loadParticleAssets: suspend (Int) -> ParticleAssets,
    private val nowMs: () -> Double,
) : DisposableContainer() {
    constructor(
        renderContext: QuestRenderContext,
        particleAssetLoader: ParticleAssetLoader,
    ) : this(
        renderContext = renderContext,
        loadParticleAssets = { mapId -> particleAssetLoader.load(mapId) },
        nowMs = { window.performance.now() },
    )

    private data class Emitter(
        val spawn: ParticleSpawn,
        val position: Vector3,
        val resolveAttachedPosition: (() -> Vector3?)?,
        val effect: ParticleEffectData,
        val particleTexture: ParticleTexture,
        var age: Double = 0.0,
        var emissionAccumulator: Double = 0.0,
        var liveParticleCount: Int = 0,
    )

    private data class LiveParticle(
        val emitter: Emitter,
        val mesh: Mesh,
        val material: MeshBasicMaterial,
        val effect: ParticleEffectData,
        val velocity: Vector3,
        val targetPosition: Vector3?,
        var interpolationFactor: Double,
        val verticalVelocityDelta: Double,
        val scaleMultiplier: Double,
        val nativeWidth: Double,
        val nativeHeight: Double,
        val initialRotation: Double,
        val effectNtFlags: Int,
        val initialRed: Double,
        val initialGreen: Double,
        val initialBlue: Double,
        val redDelta: Double,
        val greenDelta: Double,
        val blueDelta: Double,
        val lifetime: Double,
        var age: Double = 0.0,
    )

    private val emitters = mutableListOf<Emitter>()
    private val particles = mutableListOf<LiveParticle>()
    internal val emitterCount: Int
        get() = emitters.size

    internal val liveParticleCount: Int
        get() = particles.size

    internal fun liveParticleCount(particleId: Int): Int =
        particles.count { it.emitter.spawn.particleId == particleId }

    private var previousTimeMs = nowMs()
    private var frameAccumulator = 0.0
    private var reportedLiveParticles = false

    suspend fun setSpawns(
        spawns: List<ParticleSpawn>,
        resolveTemplateMapIds: (ParticleSpawn) -> Set<Int>,
        resolveEntityPosition: (Int) -> Vector3?,
    ) {
        clear()
        if (spawns.isEmpty()) return

        for (spawn in spawns) {
            val position = resolvePosition(spawn.origin, resolveEntityPosition)
            if (position == null) {
                val yOffset = (spawn.origin as ParticleSpawnOrigin.EntityPosition).yOffset
                addFallbackMarker(
                    spawn,
                    Vector3(0.0, yOffset.toDouble(), 0.0),
                    "runtime entity position unavailable",
                )
                continue
            }
            val mapIds = resolveTemplateMapIds(spawn)
            var addedEmitter = false
            for (mapId in mapIds) {
                val assets = try {
                    loadParticleAssets(mapId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.warn(e) {
                        "Could not load particle assets for map $mapId " +
                            "(particle ${spawn.particleId})."
                    }
                    continue
                }
                val effect = assets.effect(spawn.particleId)
                if (effect == null) {
                    logger.warn {
                        "Particle template ${spawn.particleId} is unavailable for map $mapId."
                    }
                    continue
                }
                val texture = assets.texturesById[effect.textureId]
                if (texture == null) {
                    logger.warn {
                        "Particle texture ${effect.textureId} is unavailable for particle " +
                            "${spawn.particleId} on map $mapId."
                    }
                    continue
                }
                val resolveAttachedPosition =
                    if (spawn.source is ParticleSpawnSource.DatObject) {
                        { resolvePosition(spawn.origin, resolveEntityPosition) }
                    } else {
                        null
                    }
                emitters.add(
                    Emitter(spawn, position, resolveAttachedPosition, effect, texture)
                )
                addedEmitter = true
                // Global templates do not vary by map, so one successfully loaded copy is enough.
                if (spawn.particleId < GLOBAL_PARTICLE_EFFECT_COUNT) break
            }
            if (!addedEmitter) {
                addFallbackMarker(spawn, position, "template unavailable")
            }
        }
        previousTimeMs = nowMs()
        frameAccumulator = 0.0
        reportedLiveParticles = false
        logger.info {
            "Loaded ${emitters.size} particle emitters and " +
                "${renderContext.particleMarkers.children.size} fallback markers."
        }
    }

    fun beforeRender() {
        val now = nowMs()
        // Client timing is frame-based at 30 Hz. Clamp background-tab gaps to avoid a burst storm.
        val elapsedFrames = ((now - previousTimeMs) / (1000.0 / 30.0)).coerceIn(0.0, 3.0)
        previousTimeMs = now
        frameAccumulator += elapsedFrames
        val framesToUpdate = frameAccumulator.toInt()
        if (framesToUpdate == 0) return
        frameAccumulator -= framesToUpdate

        repeat(framesToUpdate) {
            for (emitter in emitters) emitter.updateEmitter()
            updateParticles()
        }
        if (!reportedLiveParticles && particles.isNotEmpty()) {
            reportedLiveParticles = true
            logger.info { "Created ${particles.size} live particles." }
        }
    }

    private fun updateParticles() {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val particle = iterator.next()
            particle.age += 1.0
            if (particle.age >= particle.lifetime) {
                renderContext.particleMarkers.remove(particle.mesh)
                particle.material.dispose()
                particle.emitter.liveParticleCount--
                iterator.remove()
                continue
            }

            when (particle.effect.particleType) {
                1 -> {
                    particle.interpolationFactor *= particle.effect.motionMultiplier
                    val target = particle.targetPosition
                    if (target != null) {
                        particle.mesh.position.x +=
                            (target.x - particle.mesh.position.x) * particle.interpolationFactor
                        particle.mesh.position.y +=
                            (target.y - particle.mesh.position.y) * particle.interpolationFactor
                        particle.mesh.position.z +=
                            (target.z - particle.mesh.position.z) * particle.interpolationFactor
                    }
                }
                2 -> Unit // The client type-2 constructor initializes translational velocity to 0.
                else -> {
                    // Type 0 integrates position first, then mutates velocity for the next frame.
                    particle.mesh.position.add(particle.velocity)
                    particle.velocity.x *= particle.effect.motionMultiplier
                    particle.velocity.z *= particle.effect.motionMultiplier
                    particle.velocity.y += particle.verticalVelocityDelta
                }
            }
            // Preserve the client's scale curve while keeping very small effects inspectable in
            // the editor's full-map view. Applying the minimum only to the initial scale would
            // incorrectly amplify the effect's subsequent per-frame growth.
            val scaleFactor = particle.scaleMultiplier.pow(particle.age)
            particle.mesh.setInspectableScale(
                particle.nativeWidth * scaleFactor,
                particle.nativeHeight * scaleFactor,
            )
            particle.mesh.quaternion.copy(renderContext.camera.quaternion)
            particle.mesh.rotateZ(
                particle.initialRotation + particle.effect.radius * particle.age * PI / 180.0
            )

            val lifeFraction = particle.age / particle.lifetime
            val fade = particleFade(particle.effect, lifeFraction)
            val fadeRgb = (particle.effectNtFlags and 1) != 0
            particle.material.opacity = if (fadeRgb) 1.0 else fade
            val colorMultiplier = if (fadeRgb) fade else 1.0
            particle.material.color.setRGB(
                ((particle.initialRed + particle.redDelta * lifeFraction) * colorMultiplier)
                    .coerceIn(0.0, 1.0),
                ((particle.initialGreen + particle.greenDelta * lifeFraction) * colorMultiplier)
                    .coerceIn(0.0, 1.0),
                ((particle.initialBlue + particle.blueDelta * lifeFraction) * colorMultiplier)
                    .coerceIn(0.0, 1.0),
            )
        }
    }

    override fun dispose() {
        clear()
        super.dispose()
    }

    private fun Emitter.updateEmitter() {
        // TObjParticle updates its emitter from the map object's position every frame. Entity-based
        // BIN opcodes instead copy the entity position only when the opcode executes.
        resolveAttachedPosition?.invoke()?.let(position::copy)
        age += 1.0
        val lifetimeFrames = spawn.lifetimeFrames
        if (lifetimeFrames != null) {
            val previewDuration = max(1, lifetimeFrames)
            if (age >= previewDuration) {
                age %= previewDuration.toDouble()
                emissionAccumulator = 0.0
            }
        }

        val data = effect
        val modulation = if (data.emissionModulationDegreesPerFrame == 0f) {
            1.0
        } else {
            abs(sin(age * data.emissionModulationDegreesPerFrame * PI / 180.0))
        }
        emissionAccumulator += max(0.0, data.emissionRate.toDouble()) * modulation
        val spawnCount = emissionAccumulator.toInt().coerceIn(0, MAX_PARTICLES_PER_BURST)
        val perEmitterLimit = max(1, MAX_LIVE_PARTICLES / emitters.size)
        val availableForEmitter = perEmitterLimit - liveParticleCount
        if (
            spawnCount == 0 ||
            particles.size >= MAX_LIVE_PARTICLES ||
            availableForEmitter <= 0
        ) return
        val particlesToCreate = minOf(
            spawnCount,
            MAX_LIVE_PARTICLES - particles.size,
            availableForEmitter,
        )
        emissionAccumulator -= particlesToCreate
        repeat(particlesToCreate) {
            createParticle(this)
        }
    }

    private fun createParticle(emitter: Emitter) {
        val effect = emitter.effect
        fun positionVariation() = if (effect.particleType == 1) {
            Random.nextDouble(-1.0, 1.0)
        } else {
            Random.nextDouble(-0.5, 0.5)
        }

        val material = MeshBasicMaterial(obj {
            map = emitter.particleTexture.texture
            color = Color(
                effect.red.toDouble().coerceIn(0.0, 1.0),
                effect.green.toDouble().coerceIn(0.0, 1.0),
                effect.blue.toDouble().coerceIn(0.0, 1.0),
            )
            transparent = true
            opacity = 1.0
            side = DoubleSide
            // The stock destination-alpha blend assumes PSO's intermediate render target. In the
            // editor, opaque map pixels have destination alpha 1, which would multiply the source
            // texture by zero and make the particle invisible. Use the equivalent visible WebGL
            // approximations for the two effect_nt modes.
            blending = if ((emitter.particleTexture.metadata.flags and 2) == 0) {
                CustomBlending
            } else {
                NormalBlending
            }
        })
        material.asDynamic().depthTest = false
        material.asDynamic().depthWrite = false
        if ((emitter.particleTexture.metadata.flags and 2) == 0) {
            // effect_nt additive textures encode their glow in RGB and may carry zero alpha.
            // ONE + ONE preserves that RGB instead of multiplying it away via SrcAlpha.
            material.asDynamic().blendSrc = OneFactor
            material.asDynamic().blendDst = OneFactor
        }
        val initialRotation = if (emitter.particleTexture.metadata.rendererType == 4) {
            Random.nextDouble(0.0, 2.0 * PI)
        } else {
            0.0
        }
        val mesh = Mesh(PARTICLE_GEOMETRY, material)
        val randomizedPosition = emitter.position.clone()
        randomizedPosition.x += positionVariation() * effect.xVariation
        randomizedPosition.y += if (effect.particleType == 0 && (effect.textureFlags and 2) != 0) {
            effect.yVariation.toDouble()
        } else {
            positionVariation() * effect.yVariation
        }
        randomizedPosition.z += positionVariation() * effect.zVariation
        if (effect.particleType == 1) {
            // Type 1 starts at the emitter's XZ and interpolates toward the randomized endpoint.
            mesh.position.set(emitter.position.x, randomizedPosition.y, emitter.position.z)
        } else {
            mesh.position.copy(randomizedPosition)
        }

        val scale = max(0.01, effect.initialScale + Random.nextDouble() * effect.randomScaleRange)
        val metadata = emitter.particleTexture.metadata
        val nativeWidth = metadata.width * scale
        val nativeHeight = metadata.height * scale
        mesh.setInspectableScale(nativeWidth.toDouble(), nativeHeight.toDouble())
        mesh.quaternion.copy(renderContext.camera.quaternion)
        mesh.rotateZ(initialRotation)
        mesh.name = "Particle ${emitter.spawn.particleId}"
        mesh.userData = emitter.spawn
        mesh.renderOrder = 1000
        mesh.frustumCulled = false
        renderContext.particleMarkers.add(mesh)

        val velocity = if (effect.particleType == 0) {
            val speed = effect.horizontalSpeed +
                Random.nextDouble() * effect.randomHorizontalSpeedRange
            val direction = Random.nextDouble(0.0, 2.0 * PI)
            Vector3(
                cos(direction) * speed,
                effect.verticalSpeed + Random.nextDouble() * effect.randomVerticalSpeedRange,
                sin(direction) * speed,
            )
        } else {
            Vector3()
        }
        val lifetime = max(
            1.0,
            effect.lifetimeFrames + Random.nextDouble() * max(0, effect.randomLifetimeFrames),
        )
        particles.add(LiveParticle(
            emitter = emitter,
            mesh = mesh,
            material = material,
            effect = effect,
            velocity = velocity,
            targetPosition = if (effect.particleType == 1) randomizedPosition else null,
            interpolationFactor = if (effect.particleType == 1) {
                Random.nextDouble(-0.5, 0.5)
            } else {
                0.0
            },
            verticalVelocityDelta = effect.verticalVelocityDelta.toDouble(),
            scaleMultiplier = effect.scaleMultiplier.toDouble(),
            nativeWidth = nativeWidth.toDouble(),
            nativeHeight = nativeHeight.toDouble(),
            initialRotation = initialRotation,
            effectNtFlags = metadata.flags,
            initialRed = effect.red.toDouble(),
            initialGreen = effect.green.toDouble(),
            initialBlue = effect.blue.toDouble(),
            redDelta = effect.redDelta.toDouble(),
            greenDelta = effect.greenDelta.toDouble(),
            blueDelta = effect.blueDelta.toDouble(),
            lifetime = lifetime,
        ))
        emitter.liveParticleCount++
    }

    private fun particleFade(effect: ParticleEffectData, lifeFraction: Double): Double {
        val fadeIn = if (effect.fadeInFraction > 0f) {
            lifeFraction / effect.fadeInFraction
        } else {
            1.0
        }
        val fadeOut = if (effect.fadeOutFraction > 0f) {
            (1.0 - lifeFraction) / effect.fadeOutFraction
        } else {
            1.0
        }
        return minOf(1.0, fadeIn, fadeOut).coerceAtLeast(0.0)
    }

    private fun Object3D.setInspectableScale(nativeWidth: Double, nativeHeight: Double) {
        val smallestDimension = minOf(abs(nativeWidth), abs(nativeHeight))
        val previewMultiplier = if (smallestDimension > 0.0) {
            max(1.0, MIN_PARTICLE_PREVIEW_SIZE / smallestDimension)
        } else {
            1.0
        }
        scale.set(nativeWidth * previewMultiplier, nativeHeight * previewMultiplier, 1.0)
    }

    private fun resolvePosition(
        origin: ParticleSpawnOrigin,
        resolveEntityPosition: (Int) -> Vector3?,
    ): Vector3? = when (origin) {
        is ParticleSpawnOrigin.WorldPosition ->
            Vector3(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble())
        is ParticleSpawnOrigin.EntityPosition -> resolveEntityPosition(origin.entityId)
            ?.clone()
            ?.also { it.y += origin.yOffset }
    }

    private fun addFallbackMarker(spawn: ParticleSpawn, position: Vector3, reason: String) {
        val mesh = Mesh(FALLBACK_GEOMETRY, FALLBACK_MATERIAL)
        mesh.position.copy(position)
        mesh.name = "Particle ${spawn.particleId} ($reason)"
        mesh.userData = spawn
        renderContext.particleMarkers.add(mesh)
    }

    private fun clear() {
        particles.forEach { it.material.dispose() }
        particles.clear()
        emitters.clear()
        val container = renderContext.particleMarkers
        while (container.children.isNotEmpty()) {
            container.remove(container.children.last())
        }
    }

    private companion object {
        const val MAX_LIVE_PARTICLES = 512
        const val MAX_PARTICLES_PER_BURST = 16
        val PARTICLE_GEOMETRY = PlaneGeometry(1.0, 1.0)
        val FALLBACK_GEOMETRY = CylinderGeometry(4.0, 4.0, 16.0, 12)
        val FALLBACK_MATERIAL = MeshBasicMaterial(obj {
            color = Color(0xFFD000)
            transparent = true
            opacity = 0.75
        })
    }
}
