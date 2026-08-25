package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.fileFormats.Vec3
import world.phantasmal.psolib.fileFormats.ninja.angleToRad
import world.phantasmal.psolib.fileFormats.ninja.radToAngle
import kotlin.math.roundToInt

enum class TeleporterColor { Blue, Red }

class QuestObject(override var floorId: Int, override val data: Buffer) : QuestEntity<ObjectType> {
    constructor(type: ObjectType, floorId: Int) : this(floorId, Buffer.withSize(OBJECT_BYTE_SIZE)) {
        setObjectDefaultData(type, data)
        this.type = type
    }

    var typeId: Short
        get() = data.getShort(0)
        set(value) {
            data.setShort(0, value)
        }

    override var type: ObjectType
        get() = objectTypeFromId(typeId)
        set(value) {
            typeId = value.typeId ?: -1
        }

    var id: Short
        get() = data.getShort(8)
        set(value) {
            data.setShort(8, value)
        }

    var groupId: Short
        get() = data.getShort(10)
        set(value) {
            data.setShort(10, value)
        }

    /** The single digit rendered on a Forest Door, derived from param4's second-lowest byte. */
    val forestDoorDigit: Int
        get() = ((data.getInt(52) ushr 8) and 0xFF) % 10

    override var sectionId: Short
        get() = data.getShort(12)
        set(value) {
            data.setShort(12, value)
        }

    override var position: Vec3
        get() = Vec3(data.getFloat(16), data.getFloat(20), data.getFloat(24))
        set(value) {
            setPosition(value.x, value.y, value.z)
        }

    override var rotation: Vec3
        get() = Vec3(
            angleToRad(data.getInt(28)),
            angleToRad(data.getInt(32)),
            angleToRad(data.getInt(36)),
        )
        set(value) {
            setRotation(value.x, value.y, value.z)
        }

    val scriptLabel: Int?
        get() = activeScriptLabel

    @Deprecated("Use scriptLabel instead.", ReplaceWith("scriptLabel"))
    val scriptLabel2: Int?
        get() = if (type == ObjectType.RicoMessagePod) scriptLabel else null

    /**
     * Byte offset that can contain this object's script entry label, regardless of whether the
     * object's current mode enables the callback.
     */
    val possibleScriptLabelOffset: Int?
        get() = when (typeId.toInt() and 0xFFFF) {
            0x0012, // TObjQuestCol
            0x0015, // TObjQuestColA
            0x0026, // TOChatSensor
            0x008B, // TObjComputer
            0x02B7, // TObjGbAdvance
            0x02B8, // TObjQuestColALock2
            0x02BA, // TObjQuestCol2
            -> 52

            0x0023, // TOAttackableCol
            0x008D, // TOCapsuleAncient01
            0x0104, // TOComputerMachine01
            0x0155, // TOMonumentAncient01
            0x0229, // TOCapsuleLabo
            -> 60

            else -> null
        }

    /** Byte offset of the script label that is active in the object's current mode. */
    val activeScriptLabelOffset: Int?
        get() {
            val offset = possibleScriptLabelOffset ?: return null
            return when (typeId.toInt() and 0xFFFF) {
                0x0023 -> offset.takeIf { data.getInt(it) > 0 }
                0x0026 -> offset.takeIf { data.getInt(28) == 0 }
                0x02B8,
                0x02BA,
                -> offset.takeIf { data.getInt(56) <= 0 }

                else -> offset
            }
        }

    /** Script entry label referenced by this object's current mode. */
    val activeScriptLabel: Int?
        get() = activeScriptLabelOffset?.let(data::getInt)

    /**
     * The offset of the model property or -1 if this object doesn't have a model property.
     */
    val modelOffset: Int
        get() = when (type) {
            ObjectType.Probe,
            -> 40

            ObjectType.Saw,
            ObjectType.LaserDetect,
            -> 48

            ObjectType.Sonic,
            ObjectType.LittleCryotube,
            ObjectType.Cactus,
            ObjectType.BigBrownRock,
            ObjectType.BigBlackRocks,
            ObjectType.BeeHive,
            -> 52

            ObjectType.ForestConsole,
            -> 56

            ObjectType.PrincipalWarp,
            ObjectType.LaserFence,
            ObjectType.LaserSquareFence,
            ObjectType.LaserFenceEx,
            ObjectType.LaserSquareFenceEx,
            -> 60

            else -> -1
        }

    var model: Int
        get() = when (type) {
            ObjectType.Probe,
            -> data.getFloat(40).safeRoundToInt()

            ObjectType.Saw,
            ObjectType.LaserDetect,
            -> data.getFloat(48).safeRoundToInt()

            ObjectType.Sonic,
            ObjectType.LittleCryotube,
            ObjectType.Cactus,
            ObjectType.BigBrownRock,
            ObjectType.BigBlackRocks,
            ObjectType.BeeHive,
            -> data.getInt(52)

            ObjectType.ForestConsole,
            -> data.getInt(56)

            ObjectType.PrincipalWarp,
            ObjectType.LaserFence,
            ObjectType.LaserSquareFence,
            ObjectType.LaserFenceEx,
            ObjectType.LaserSquareFenceEx,
            -> data.getInt(60)

            else -> throw IllegalArgumentException("$type doesn't have a model property.")
        }
        set(value) {
            when (type) {
                ObjectType.Probe,
                -> data.setFloat(40, value.toFloat())

                ObjectType.Saw,
                ObjectType.LaserDetect,
                -> data.setFloat(48, value.toFloat())

                ObjectType.Sonic,
                ObjectType.LittleCryotube,
                ObjectType.Cactus,
                ObjectType.BigBrownRock,
                ObjectType.BigBlackRocks,
                ObjectType.BeeHive,
                -> data.setInt(52, value)

                ObjectType.ForestConsole,
                -> data.setInt(56, value)

                ObjectType.PrincipalWarp,
                ObjectType.LaserFence,
                ObjectType.LaserSquareFence,
                ObjectType.LaserFenceEx,
                ObjectType.LaserSquareFenceEx,
                -> data.setInt(60, value)

                else -> throw IllegalArgumentException("$type doesn't have a model property.")
            }
        }

    val destinationPositionOffset: Int
        get() = when (type) {
            ObjectType.Warp,
            ObjectType.PrincipalWarp,
            ObjectType.RuinsWarpSiteToSite,
            ObjectType.InstaWarp,
            ObjectType.LabCeilingWarp,
            -> 40
            else -> -1
        }

    /**
     * Only valid when [destinationPositionOffset] is nonnegative.
     */
    var destinationPosition: Vec3
        get() = Vec3(
            data.getFloat(40),
            data.getFloat(44),
            data.getFloat(48),
        )
        set(value) {
            setDestinationPosition(value.x, value.y, value.z)
        }

    /**
     * Only valid when [destinationPositionOffset] is nonnegative.
     */
    var destinationPositionX: Float
        get() = data.getFloat(40)
        set(value) {
            data.setFloat(40, value)
        }

    /**
     * Only valid when [destinationPositionOffset] is nonnegative.
     */
    var destinationPositionY: Float
        get() = data.getFloat(44)
        set(value) {
            data.setFloat(44, value)
        }

    /**
     * Only valid when [destinationPositionOffset] is nonnegative.
     */
    var destinationPositionZ: Float
        get() = data.getFloat(48)
        set(value) {
            data.setFloat(48, value)
        }

    val destinationRotationYOffset: Int
        get() = when (type) {
            ObjectType.Warp,
            ObjectType.PrincipalWarp,
            ObjectType.RuinsWarpSiteToSite,
            ObjectType.InstaWarp,
            ObjectType.LabCeilingWarp,
            -> 52
            else -> -1
        }

    /**
     * Only valid when [destinationRotationYOffset] is nonnegative.
     */
    var destinationRotationY: Float
        get() = angleToRad(data.getInt(52))
        set(value) {
            data.setInt(52, radToAngle(value))
        }

    val destinationFloorOffset: Int
        get() = when (type) {
            ObjectType.Teleporter,
            ObjectType.QuestWarp,
            ObjectType.MainRagolTeleporterBattleInNextArea,
            ObjectType.RuinsTeleporter,
            ObjectType.TeleporterEp2,
            ObjectType.WarpInBarbaRayRoom,
            -> 52
            else -> -1
        }

    /** Only valid when [destinationFloorOffset] is nonnegative. */
    var destinationFloor: Int
        get() = data.getInt(destinationFloorOffset)
        set(value) {
            data.setInt(destinationFloorOffset, value)
        }

    val teleporterColor: TeleporterColor?
        get() = when (type) {
            ObjectType.Teleporter,
            ObjectType.QuestWarp,
            ObjectType.TeleporterEp2,
            ObjectType.WarpInBarbaRayRoom,
            -> when (data.getInt(60)) {
                0 -> TeleporterColor.Blue
                1 -> TeleporterColor.Red
                else -> null
            }
            ObjectType.RuinsTeleporter ->
                if (data.getInt(60) < 0) TeleporterColor.Red else TeleporterColor.Blue
            else -> null
        }

    init {
        require(data.size == OBJECT_BYTE_SIZE) {
            "Data size should be $OBJECT_BYTE_SIZE but was ${data.size}."
        }
    }

    override fun setPosition(x: Float, y: Float, z: Float) {
        data.setFloat(16, x)
        data.setFloat(20, y)
        data.setFloat(24, z)
    }

    override fun setRotation(x: Float, y: Float, z: Float) {
        data.setInt(28, radToAngle(x))
        data.setInt(32, radToAngle(y))
        data.setInt(36, radToAngle(z))
    }

    fun setDestinationPosition(x: Float, y: Float, z: Float) {
        data.setFloat(40, x)
        data.setFloat(44, y)
        data.setFloat(48, z)
    }
}
