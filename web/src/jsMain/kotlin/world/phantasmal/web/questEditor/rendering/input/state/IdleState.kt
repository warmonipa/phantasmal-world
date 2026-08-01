package world.phantasmal.web.questEditor.rendering.input.state

import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleInteractionEvent
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawn
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawnOrigin
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawnSource
import world.phantasmal.web.core.minus
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.Vector2
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.models.QuestEntityModel
import world.phantasmal.web.questEditor.rendering.EntityInstanceContainer
import world.phantasmal.web.questEditor.rendering.input.*

internal fun ParticleSpawn.primaryInteractionEvent(): ParticleInteractionEvent? =
    interactionEvents.minWithOrNull(compareBy({ it.label }, { it.kind.ordinal }))

class IdleState(
    private val ctx: StateContext,
    private val entityManipulationEnabled: Boolean,
) : State() {
    private var panning = false
    private var rotating = false
    private var zooming = false
    private var pressedParticle: ParticleSpawn? = null
    private val pointerDevicePosition = Vector2()
    private var shouldCheckHighlight = false

    override fun processEvent(event: Evt): State {
        // Don't highlight or manipulate entities in forced panning/rotating mode.
        val forcedPanningRotatingMode = when (event) {
            is KeyboardEvt -> event.key == "Control"
            is PointerEvt -> event.ctrlKey
            else -> false
        }

        when (event) {
            is KeyboardEvt -> {
                if (forcedPanningRotatingMode) {
                    ctx.setHighlightedEntity(null)
                } else if (entityManipulationEnabled) {
                    val quest = ctx.quest.value
                    val entity = ctx.selectedEntity.value

                    if (quest != null && entity != null && event.key == "Delete") {
                        ctx.finalizeEntityDelete(quest, entity)
                    }
                }
            }

            is PointerDownEvt -> {
                // A canceled pointer sequence can leave the previous target behind.
                pressedParticle = null

                val particle =
                    if (forcedPanningRotatingMode || event.buttons != 1) null
                    else ctx.pickParticle(event.pointerDevicePosition)
                        ?.takeIf { it.primaryInteractionEvent() != null }
                val pick =
                    if (forcedPanningRotatingMode || particle != null) null
                    else pickEntity(event.pointerDevicePosition)

                when (event.buttons) {
                    1 -> {
                        if (particle != null) {
                            // Remember the moving particle instead of trying to pick it again on
                            // pointer-up. Navigating here would switch tabs halfway through the
                            // browser's click sequence and let the remaining events reactivate 3D.
                            pressedParticle = particle
                        } else if (pick == null) {
                            panning = true
                        } else {
                            ctx.selectViewportEntity(pick.entity)

                            if (entityManipulationEnabled) {
                                return TranslationState(
                                    ctx,
                                    pick.entity,
                                    pick.dragAdjust,
                                    pick.grabOffset,
                                )
                            }
                        }
                    }
                    2 -> {
                        if (pick == null) {
                            rotating = true
                        } else {
                            ctx.selectViewportEntity(pick.entity)

                            if (entityManipulationEnabled) {
                                return RotationState(
                                    ctx,
                                    pick.entity,
                                    pick.grabOffset,
                                )
                            }
                        }
                    }
                    4 -> {
                        zooming = true
                    }
                }
            }

            is PointerUpEvt -> {
                if (panning) {
                    updateCameraTarget()
                }

                panning = false
                rotating = false
                zooming = false

                val clickedParticle = pressedParticle
                pressedParticle = null

                if (clickedParticle != null) {
                    clickedParticle.primaryInteractionEvent()?.let { interactionEvent ->
                        ctx.navigateToScriptLabel(interactionEvent.label)
                    }
                } else if (!event.movedSinceLastPointerDown &&
                    pickEntity(event.pointerDevicePosition) == null
                ) {
                    // If the user clicks on nothing, deselect the currently selected entity.
                    ctx.setSelectedEntity(null)
                    pickAndHighlightMesh()
                }
            }

            is PointerMoveEvt -> {
                if (!panning && !rotating && !zooming) {
                    // User is hovering.
                    if (forcedPanningRotatingMode) {
                        ctx.setHighlightedEntity(null)
                    } else {
                        pointerDevicePosition.copy(event.pointerDevicePosition)
                        shouldCheckHighlight = true
                    }
                }
            }

            is PointerOutEvt -> {
                // Don't clear pressedParticle here. QuestInputManager intentionally overlays the
                // canvas with a pointer trap after pointer-down, producing a synthetic pointer-out
                // (with inconsistent buttons values across browsers) before the window-level
                // pointer-up consumes and clears the locked particle.
                ctx.setHighlightedEntity(null)
                shouldCheckHighlight = false
                ctx.renderContext.canvas.title = ""
            }

            is EntityDragEnterEvt -> {
                val quest = ctx.quest.value
                val area = ctx.area.value

                if (quest != null && area != null) {
                    return CreationState(ctx, event, quest, area)
                }
            }

            else -> return this
        }

        return this
    }

    override fun beforeRender() {
        if (shouldCheckHighlight) {
            ctx.setHighlightedEntity(pickEntity(pointerDevicePosition)?.entity)

            // Update particle marker tooltip via the canvas's title attribute. The browser
            // shows a native tooltip after the user hovers for ~1s.
            val particle = ctx.pickParticle(pointerDevicePosition)
            ctx.renderContext.canvas.title = if (particle != null) {
                val origin = when (val value = particle.origin) {
                    is ParticleSpawnOrigin.WorldPosition ->
                        "(${value.x}, ${value.y}, ${value.z})"
                    is ParticleSpawnOrigin.EntityPosition ->
                        "entity 0x${value.entityId.toString(16).uppercase()} +Y ${value.yOffset}"
                }
                val drawRange = if (particle.hasExtendedDrawRange) ", extended draw range" else ""
                val lifetime = when (particle.source) {
                    is ParticleSpawnSource.DatObject -> "persistent DAT object"
                    is ParticleSpawnSource.Opcode -> "${particle.lifetimeFrames} frames"
                }
                val events = particle.interactionEvents
                    .sortedWith(compareBy({ it.label }, { it.kind.ordinal }))
                    .joinToString { event ->
                        val kind = when (event.kind) {
                            ParticleInteractionEvent.Kind.Call -> "call"
                            ParticleInteractionEvent.Kind.Talk -> "talk"
                        }
                        "event ${event.label} ($kind)"
                    }
                    .let { if (it.isEmpty()) "" else ", $it" }
                "Particle ${particle.particleId} @ $origin, $lifetime$drawRange$events"
            } else {
                ""
            }

            shouldCheckHighlight = false
        }
    }

    override fun cancel() {
        // Do nothing.
    }

    private fun updateCameraTarget() {
        // If the user moved the camera, try setting the camera target to a better point.
        ctx.pickGround(ZERO_VECTOR_2)?.let { intersection ->
            ctx.cameraInputManager.setTarget(intersection.point)
        }
    }

    /**
     * @param pointerPosition pointer coordinates in normalized device space
     */
    private fun pickEntity(pointerPosition: Vector2): Pick? {
        // Find the nearest entity under the pointer.
        val intersection = ctx.intersectObject(
            pointerPosition,
            ctx.renderContext.entities,
        ) { it.`object`.visible }

        intersection ?: return null

        val entityInstancedMesh = intersection.`object`.userData
        val instanceIndex = intersection.instanceId

        if (instanceIndex == null || entityInstancedMesh !is EntityInstanceContainer) {
            return null
        }

        val entity = entityInstancedMesh.getInstanceAt(instanceIndex).entity
        val entityPosition = entity.worldPosition.value

        // Vector from the point where we grab the entity to its position.
        val grabOffset = entityPosition - intersection.point

        // Vector from the point where we grab the entity to the point on the ground right beneath
        // its position. The same as grabOffset when an entity is standing on the ground.
        val dragAdjust = grabOffset.clone()

        // Find vertical distance to the ground.
        ctx.intersectObject(
            origin = entityPosition,
            direction = DOWN_VECTOR,
            ctx.renderContext.collisionGeometry,
        )?.let { groundIntersection ->
            dragAdjust.y -= groundIntersection.distance
        }

        return Pick(entity, grabOffset, dragAdjust)
    }

    private fun pickAndHighlightMesh() {
        if (ctx.devMode.value) {
            val intersection = ctx.intersectObject(
                pointerDevicePosition,
                ctx.renderContext.renderGeometry,
            ) { it.`object`.visible }

            ctx.setHighlightedMesh(intersection?.`object` as Mesh?)
        } else {
            ctx.setHighlightedMesh(null)
        }
    }

    private class Pick(
        val entity: QuestEntityModel<*, *>,

        /**
         * Vector that points from the grabbing point (somewhere on the model's surface) to the
         * entity's origin.
         */
        val grabOffset: Vector3,

        /**
         * Vector that points from the grabbing point to the terrain point directly under the
         * entity's origin.
         */
        val dragAdjust: Vector3,
    )

    companion object {
        private val ZERO_VECTOR_2 = Vector2(0.0, 0.0)
        private val DOWN_VECTOR = Vector3(0.0, -1.0, 0.0)
    }
}
