package world.phantasmal.web.questEditor.rendering

import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawn
import world.phantasmal.web.externals.three.Color
import world.phantasmal.web.externals.three.CylinderGeometry
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.webui.DisposableContainer
import world.phantasmal.webui.obj

/**
 * Renders a placeholder marker (yellow cylinder) at every statically resolved `particle_v3`
 * invocation site so the editor user can see where script-spawned particle effects would appear.
 *
 * Each marker stores its [ParticleSpawn] in its `userData`, so a raycaster pass against
 * [QuestRenderContext.particleMarkers] can recover the spawn data on hit (used for hover tooltip).
 *
 * The actual particle visuals from PSO's `particleentry.dat` are not reproduced here.
 */
class ParticleMarkerManager(
    private val renderContext: QuestRenderContext,
) : DisposableContainer() {
    override fun dispose() {
        clear()
        super.dispose()
    }

    fun setSpawns(spawns: List<ParticleSpawn>) {
        clear()

        for (spawn in spawns) {
            val mesh = Mesh(GEOMETRY, MATERIAL)
            mesh.position.set(spawn.x.toDouble(), spawn.y.toDouble(), spawn.z.toDouble())
            mesh.name = "Particle ${spawn.particleId}"
            mesh.userData = spawn
            renderContext.particleMarkers.add(mesh)
        }
    }

    /**
     * Removes all marker meshes. Geometry and material are shared and intentionally retained.
     */
    private fun clear() {
        val container = renderContext.particleMarkers
        while (container.children.isNotEmpty()) {
            container.remove(container.children[container.children.size - 1])
        }
    }

    companion object {
        // Geometry and material are shared across every marker and live for the lifetime of the
        // application — they are NEVER disposed (callers must not pass markers to
        // disposeObject3DResources because it would dispose the shared resources).
        private val GEOMETRY = CylinderGeometry(
            radiusTop = 4.0,
            radiusBottom = 4.0,
            height = 16.0,
            radialSegments = 12,
        )
        private val MATERIAL = MeshBasicMaterial(obj {
            color = Color(0xFFD000)
            transparent = true
            opacity = 0.75
        })
    }
}
