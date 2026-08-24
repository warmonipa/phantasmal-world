package world.phantasmal.web.questEditor.models

import world.phantasmal.psolib.fileFormats.quest.ChallengeModeSeedSimulation

/** Events the client will actually execute for the current challenge seed. */
internal fun effectiveQuestEvents(
    simulation: ChallengeModeSeedSimulation?,
    sourceEvents: List<QuestEventModel>,
): List<QuestEventModel> {
    if (simulation == null) return sourceEvents
    val wavesBySource = simulation.waves.groupBy { it.floorId to it.sourceEventId }

    return sourceEvents.flatMap { source ->
        if (source.cmWaveSettings.value == null) {
            listOf(source)
        } else {
            wavesBySource[source.floorId to source.id.value].orEmpty().map { wave ->
                val actions = wave.triggeredEventId?.let { nextEventId ->
                    mutableListOf<QuestEventActionModel>(QuestEventActionModel.TriggerEvent(nextEventId))
                } ?: source.actions.value.mapTo(mutableListOf(), ::copyEventAction)

                QuestEventModel(
                    id = wave.materializedEventId,
                    floorId = wave.floorId,
                    sectionId = wave.roomId,
                    waveId = wave.waveNumber,
                    delay = wave.delay,
                    unknown = 0,
                    actions = actions,
                    cmWaveSettings = null,
                    challengeSourceEventId = wave.sourceEventId,
                )
            }
        }
    }
}

private fun copyEventAction(action: QuestEventActionModel): QuestEventActionModel =
    when (action) {
        is QuestEventActionModel.SpawnNpcs ->
            QuestEventActionModel.SpawnNpcs(action.sectionId.value, action.appearFlag.value)
        is QuestEventActionModel.Door.Unlock ->
            QuestEventActionModel.Door.Unlock(action.doorId.value)
        is QuestEventActionModel.Door.Lock ->
            QuestEventActionModel.Door.Lock(action.doorId.value)
        is QuestEventActionModel.TriggerEvent ->
            QuestEventActionModel.TriggerEvent(action.eventId.value)
    }
