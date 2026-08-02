package world.phantasmal.web.questEditor.models

import world.phantasmal.cell.Cell
import world.phantasmal.cell.mutableCell
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawnEntry
import world.phantasmal.web.core.euler
import world.phantasmal.web.core.timesAssign
import world.phantasmal.web.core.toEuler
import world.phantasmal.web.externals.three.Quaternion
import world.phantasmal.web.externals.three.Vector3

/**
 * Model representing a challenge mode random monster spawn point.
 * These are visualized but not selectable/editable.
 */
class ChallengeMonsterSpawnModel(
    /** Logical quest floor containing this Challenge Mode spawn. */
    val floorId: Int,
    val roomId: Int,
    val entry: DatCmRandomSpawnEntry,
    section: SectionModel?,
) {
    private val _position = mutableCell(calculateWorldPosition(entry, section))

    private val _rotation = mutableCell(calculateWorldRotation(entry, section))

    val position: Cell<Vector3> = _position
    val rotation: Cell<Vector3> = _rotation
    val sectionId: Int = roomId

    companion object {
        private const val SCALE_FACTOR = 2.0 * kotlin.math.PI / 65536.0

        /**
         * Calculate world position from section-relative position.
         * Uses the same transformation as QuestEntityModel: applyEuler + add position
         */
        private fun calculateWorldPosition(
            entry: DatCmRandomSpawnEntry,
            section: SectionModel?
        ): Vector3 {
            // Section-relative position
            val localPos = Vector3(
                entry.x.toDouble(),
                entry.y.toDouble(),
                entry.z.toDouble(),
            )

            if (section == null) {
                // If no section, use local coordinates directly
                return localPos
            }

            // Apply section transformation the same way as normal entities:
            // 1. Apply Euler rotation
            // 2. Add section position
            return localPos.clone().applyEuler(section.rotation).add(section.position)
        }

        private fun calculateWorldRotation(
            entry: DatCmRandomSpawnEntry,
            section: SectionModel?,
        ): Vector3 {
            val localRotation = euler(
                entry.angleX.toDouble() * SCALE_FACTOR,
                entry.angleY.toDouble() * SCALE_FACTOR,
                entry.angleZ.toDouble() * SCALE_FACTOR,
            )
            if (section == null) {
                return Vector3(localRotation.x, localRotation.y, localRotation.z)
            }

            // Position is transformed by the room first, so orientation is section * local too.
            val worldRotation = Quaternion().setFromEuler(section.rotation)
            worldRotation *= Quaternion().setFromEuler(localRotation)
            val euler = worldRotation.toEuler()
            return Vector3(euler.x, euler.y, euler.z)
        }
    }
}
