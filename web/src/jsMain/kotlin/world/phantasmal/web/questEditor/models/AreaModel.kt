package world.phantasmal.web.questEditor.models

import world.phantasmal.core.requireNonNegative
import world.phantasmal.psolib.Episode

class AreaModel(
    /**
     * Matches the PSO ID.
     */
    val id: Int,
    val name: String,
    val bossArea: Boolean,
    val order: Int,
    val areaVariants: List<AreaVariantModel>,
    /**
     * Area IDs are only unique within an episode, e.g. Ep. II Lab and Ep. IV Pioneer II are both
     * area 0.
     */
    val episode: Episode,
) {
    init {
        requireNonNegative(id, "id")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class.js != other::class.js) return false
        other as AreaModel
        return id == other.id && episode == other.episode
    }

    override fun hashCode(): Int = 31 * id + episode.hashCode()
}
