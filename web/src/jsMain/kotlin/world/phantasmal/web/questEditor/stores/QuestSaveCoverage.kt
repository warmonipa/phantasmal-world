package world.phantasmal.web.questEditor.stores

import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.quest.DatEntity
import world.phantasmal.psolib.fileFormats.quest.DatFile
import world.phantasmal.psolib.fileFormats.quest.writeDat
import world.phantasmal.psolib.fileFormats.quest.writeQuestToBinDat
import world.phantasmal.web.questEditor.loading.LOBBY_FLOOR_ID
import world.phantasmal.web.questEditor.models.QuestModel

/**
 * A partial save may mark the document saved only if everything it omits is unchanged since load.
 * Compare serialized data so nested entity, event, script, and Challenge edits are covered too.
 */
class QuestSaveCoverage private constructor(
    quest: QuestModel,
    private val objectFloors: Set<Int>,
    private val npcFloors: Set<Int>,
    private val eventFloors: Set<Int>,
    private val savesBin: Boolean,
) {
    private val baseline = snapshot(quest)

    fun canSave(quest: QuestModel): Boolean {
        val current = snapshot(quest)
        return baseline.bin.contentEquals(current.bin) && baseline.dat.contentEquals(current.dat)
    }

    private fun snapshot(model: QuestModel): Snapshot {
        val quest = convertQuestFromModel(model)
        val dat = writeDat(DatFile(
            objs = quest.objects.filter { it.floorId !in objectFloors }
                .map { DatEntity(it.floorId, it.data) },
            npcs = quest.npcs.filter { it.floorId !in npcFloors }
                .map { DatEntity(it.floorId, it.data) },
            events = quest.events.filter { it.floorId !in eventFloors },
            unknowns = quest.datUnknowns,
            cmRandomSpawns = quest.challengeData.cmRandomSpawns,
            cmMonsterMappings = quest.challengeData.cmMonsterMappings,
            cmConfigPool = quest.challengeData.cmConfigPool,
        ))
        val bin = if (savesBin) ByteArray(0) else {
            writeQuestToBinDat(quest, quest.version).first.bytes()
        }
        return Snapshot(bin, dat.bytes())
    }

    private class Snapshot(val bin: ByteArray, val dat: ByteArray)

    companion object {
        fun forLobby(quest: QuestModel): QuestSaveCoverage =
            QuestSaveCoverage(quest, setOf(LOBBY_FLOOR_ID), emptySet(), emptySet(), savesBin = false)

        fun forFreeRoam(
            quest: QuestModel,
            binName: String?,
            datFilesByFloor: Map<Int, Triple<String, String, String>>,
        ): QuestSaveCoverage = QuestSaveCoverage(
            quest,
            objectFloors = datFilesByFloor.filterValues { it.first.isNotEmpty() }.keys.toSet(),
            npcFloors = datFilesByFloor.filterValues { it.second.isNotEmpty() }.keys.toSet(),
            eventFloors = datFilesByFloor.filterValues { it.third.isNotEmpty() }.keys.toSet(),
            savesBin = !binName.isNullOrEmpty(),
        )

        private fun Buffer.bytes(): ByteArray = cursor().byteArray(size)
    }
}
