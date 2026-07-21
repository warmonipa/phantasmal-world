package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.fileFormats.Vec3

interface QuestEntity<Type : EntityType> {
    val type: Type

    /** Logical quest floor that owns this entity; this is not an Episode map-area ID. */
    var floorId: Int

    val data: Buffer

    var sectionId: Short

    /**
     * Section-relative position.
     */
    var position: Vec3

    var rotation: Vec3

    /**
     * Set the section-relative position.
     */
    fun setPosition(x: Float, y: Float, z: Float)

    fun setRotation(x: Float, y: Float, z: Float)
}
