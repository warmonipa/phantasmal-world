package world.phantasmal.web.questEditor.widgets

import org.w3c.dom.Node
import world.phantasmal.cell.cell
import world.phantasmal.cell.map
import world.phantasmal.web.core.widgets.UnavailableWidget
import world.phantasmal.web.questEditor.controllers.*
import world.phantasmal.webui.dom.*
import world.phantasmal.webui.widgets.*

class MonsterRandomnessWidget(
    private val ctrl: MonsterRandomnessController,
) : Widget() {
    override fun Node.createElement() =
        div {
            className = "pw-quest-editor-monster-randomness"
            tabIndex = -1

            addEventListener("focus", { ctrl.focused() }, true)

            div {
                className = "pw-quest-editor-monster-randomness-inner"
                hidden(ctrl.unavailable)

                addChild(Toolbar(
                    children = listOf(
                        ComboBox(
                            label = "Logical floor:",
                            items = ctrl.logicalFloors,
                            itemToString = { "$it" },
                            selected = ctrl.selectedLogicalFloor,
                            onSelect = ctrl::setLogicalFloor,
                        ),
                    ),
                ))

                div {
                    className = "pw-quest-editor-mr-problems"
                    hidden(ctrl.simulationProblems.map { it.isEmpty() })
                    bindChildWidgetsTo(ctrl.simulationProblems) { message, _ ->
                        object : Widget() {
                            override fun Node.createElement() = div { textContent = message }
                        }
                    }
                }

                addChild(TabContainer(
                    ctrl = ctrl,
                    createWidget = { tab ->
                        when (tab) {
                            MonsterRandomnessTab.MonsterPosition -> monsterPositionTab()
                            MonsterRandomnessTab.EnemyConfiguration -> enemyConfigurationTab()
                        }
                    },
                ))
            }
            addChild(UnavailableWidget(
                visible = ctrl.unavailable,
                message = "No quest loaded.",
            ))
        }

    private fun monsterPositionTab(): Widget = object : Widget() {
        override fun Node.createElement() =
            div {
                className = "pw-quest-editor-mr-tab"

                div {
                    className = "pw-quest-editor-mr-rooms"

                    addChild(Toolbar(
                        children = listOf(
                            Button(
                                enabled = ctrl.enabled,
                                text = "Add room",
                                iconLeft = Icon.Plus,
                                tooltip = cell("Add a new room"),
                                onClick = { ctrl.addRoom() },
                            ),
                            Button(
                                enabled = ctrl.canDeleteRoom,
                                text = "Delete",
                                iconLeft = Icon.Remove,
                                tooltip = cell("Delete selected room"),
                                onClick = { ctrl.deleteRoom() },
                            ),
                        )
                    ))
                    div {
                        className = "pw-quest-editor-mr-room-list"

                        bindChildWidgetsTo(ctrl.rooms) { room, idx ->
                            RoomItemWidget(ctrl, room, idx)
                        }
                    }
                }
                div {
                    className = "pw-quest-editor-mr-detail"

                    addChild(Toolbar(
                        children = listOf(
                            Button(
                                enabled = ctrl.canAddSpawnEntry,
                                text = "Add entry",
                                iconLeft = Icon.Plus,
                                tooltip = cell("Add a new spawn entry"),
                                onClick = { ctrl.addSpawnEntry() },
                            ),
                        )
                    ))
                    div {
                        className = "pw-quest-editor-mr-table-container"
                        hidden(ctrl.simulateSeed)

                        table {
                            className = "pw-quest-editor-mr-table"

                            thead {
                                tr {
                                    th { textContent = "#" }
                                    th { textContent = "Pos X" }
                                    th { textContent = "Pos Y" }
                                    th { textContent = "Pos Z" }
                                    th { textContent = "Rot. X" }
                                    th { textContent = "Rot. Y" }
                                    th { textContent = "Rot. Z" }
                                    th { textContent = "Unknown A9" }
                                    th { textContent = "Unknown A10" }
                                    th { }
                                }
                            }
                            tbody {
                                bindChildWidgetsTo(ctrl.selectedRoomEntries) { indexed, _ ->
                                    SpawnEntryRowWidget(ctrl, indexed)
                                }
                            }
                        }
                    }
                    div {
                        className = "pw-quest-editor-mr-table-container"
                        hidden(ctrl.simulateSeed.map { !it })

                        table {
                            className = "pw-quest-editor-mr-table"

                            thead {
                                tr {
                                    th { textContent = "#" }
                                    th { textContent = "Event" }
                                    th { textContent = "Wave" }
                                    th { textContent = "Room" }
                                    th { textContent = "Monster" }
                                    th { textContent = "Definition" }
                                    th { textContent = "Children" }
                                    th { textContent = "Pos X" }
                                    th { textContent = "Pos Y" }
                                    th { textContent = "Pos Z" }
                                }
                            }
                            tbody {
                                bindChildWidgetsTo(ctrl.simulatedMonsters) { monster, idx ->
                                    SimulatedMonsterRowWidget(ctrl, monster, idx)
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun enemyConfigurationTab(): Widget = object : Widget() {
        override fun Node.createElement() =
            div {
                className = "pw-quest-editor-mr-enemy-config"

                // Config pool section (Table 5A).
                div {
                    className = "pw-quest-editor-mr-section"

                    div {
                        className = "pw-quest-editor-mr-section-header"
                        span { textContent = "Enemy definitions" }
                        addWidget(Button(
                            enabled = ctrl.enabled,
                            text = "Add",
                            iconLeft = Icon.Plus,
                            tooltip = cell("Add enemy definition"),
                            onClick = { ctrl.addConfigPoolEntry() },
                        ))
                    }
                    div {
                        className = "pw-quest-editor-mr-table-container"

                        table {
                            className = "pw-quest-editor-mr-table"

                            thead {
                                tr {
                                    th { textContent = "#" }
                                    th { textContent = "Param 1" }
                                    th { textContent = "Param 2" }
                                    th { textContent = "Param 3" }
                                    th { textContent = "Param 4" }
                                    th { textContent = "Param 5" }
                                    th { textContent = "Param 7" }
                                    th { textContent = "Param 6" }
                                    th { textContent = "Entry index" }
                                    th { textContent = "Unknown" }
                                    th { textContent = "Min children" }
                                    th { textContent = "Max children" }
                                    th { }
                                }
                            }
                            tbody {
                                bindChildWidgetsTo(ctrl.configPoolEntries) { indexed, _ ->
                                    ConfigPoolEntryRowWidget(ctrl, indexed)
                                }
                            }
                        }
                    }
                }

                // Monsters setting section (Table 5B).
                div {
                    className = "pw-quest-editor-mr-section"

                    div {
                        className = "pw-quest-editor-mr-section-header"
                        span { textContent = "Monster weights" }
                        addWidget(Button(
                            enabled = ctrl.enabled,
                            text = "Add",
                            iconLeft = Icon.Plus,
                            tooltip = cell("Add monster weight entry"),
                            onClick = { ctrl.addMappingEntry() },
                        ))
                    }
                    div {
                        className = "pw-quest-editor-mr-table-container"

                        table {
                            className = "pw-quest-editor-mr-table"

                            thead {
                                tr {
                                    th { textContent = "#" }
                                    th { textContent = "Monster Type" }
                                    th { textContent = "Definition" }
                                    th { textContent = "Weight" }
                                    th { textContent = "Unknown" }
                                    th { }
                                }
                            }
                            tbody {
                                bindChildWidgetsTo(ctrl.monsterSettingEntries) { indexed, _ ->
                                    MappingEntryRowWidget(ctrl, indexed)
                                }
                            }
                        }
                    }
                }
            }
    }

    companion object {
        init {
            @Suppress("CssUnusedSymbol", "CssUnresolvedCustomProperty")
            // language=css
            style("""
                .pw-quest-editor-monster-randomness {
                    overflow: hidden;
                    outline: none;
                    height: 100%;
                }

                .pw-quest-editor-monster-randomness-inner {
                    display: flex;
                    flex-direction: column;
                    width: 100%;
                    height: 100%;
                    overflow: hidden;
                }

                .pw-quest-editor-monster-randomness-inner > .pw-tab-container {
                    flex: 1;
                    overflow: hidden;
                }

                .pw-quest-editor-mr-tab {
                    display: flex;
                    flex-direction: row;
                    width: 100%;
                    height: 100%;
                    overflow: hidden;
                }

                .pw-quest-editor-mr-problems {
                    flex: 0 0 auto;
                    padding: 6px 10px;
                    color: hsl(35, 90%, 75%);
                    background: hsl(35, 35%, 14%);
                    border-bottom: 1px solid hsl(35, 45%, 30%);
                    font-size: 12px;
                }

                .pw-quest-editor-mr-rooms {
                    flex: 0 0 200px;
                    display: flex;
                    flex-direction: column;
                    border-right: var(--pw-border);
                    overflow: hidden;
                }

                .pw-quest-editor-mr-room-list {
                    flex: 1;
                    overflow-y: auto;
                }

                .pw-quest-editor-mr-room-item {
                    display: flex;
                    align-items: center;
                    padding: 4px 8px;
                    cursor: pointer;
                    font-size: 12px;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }

                .pw-quest-editor-mr-room-item .pw-number-input {
                    width: 50px;
                    margin: 0 2px;
                }

                .pw-quest-editor-mr-room-item:hover {
                    background-color: hsl(0, 0%, 25%);
                }

                .pw-quest-editor-mr-room-item.pw-selected {
                    background-color: hsl(0, 0%, 30%);
                    color: hsl(0, 0%, 95%);
                }

                .pw-quest-editor-mr-detail {
                    flex: 1;
                    display: flex;
                    flex-direction: column;
                    overflow: hidden;
                }

                .pw-quest-editor-mr-enemy-config {
                    display: flex;
                    flex-direction: column;
                    width: 100%;
                    height: 100%;
                    overflow: hidden;
                }

                .pw-quest-editor-mr-section {
                    display: flex;
                    flex-direction: column;
                    min-height: 0;
                }

                .pw-quest-editor-mr-section:first-child {
                    flex: 1;
                    overflow: hidden;
                }

                .pw-quest-editor-mr-section:last-child {
                    flex: 1;
                    overflow: hidden;
                }

                .pw-quest-editor-mr-section-header {
                    display: flex;
                    align-items: center;
                    gap: 8px;
                    padding: 4px 8px;
                    font-size: 13px;
                    font-weight: bold;
                    border-bottom: var(--pw-border);
                    background-color: hsl(0, 0%, 15%);
                }

                .pw-quest-editor-mr-section-header > span {
                    flex: 1;
                }

                .pw-quest-editor-mr-table-container {
                    flex: 1;
                    overflow: auto;
                }

                .pw-quest-editor-mr-table {
                    width: 100%;
                    border-collapse: collapse;
                    font-size: 12px;
                }

                .pw-quest-editor-mr-table th {
                    position: sticky;
                    top: 0;
                    background-color: hsl(0, 0%, 18%);
                    text-align: left;
                    padding: 4px 6px;
                    white-space: nowrap;
                    border-bottom: var(--pw-border);
                    z-index: 1;
                }

                .pw-quest-editor-mr-table td {
                    padding: 2px 4px;
                    border-bottom: 1px solid hsl(0, 0%, 20%);
                }

                .pw-quest-editor-mr-table .pw-number-input {
                    width: 70px;
                }

                .pw-quest-editor-mr-table .pw-combobox {
                    width: 180px;
                }
            """.trimIndent())
        }
    }
}

private class SpawnEntryRowWidget(
    private val ctrl: MonsterRandomnessController,
    private val indexed: IndexedSpawnEntry,
) : Widget() {
    override fun Node.createElement() =
        tr {
            val entry = indexed.entry
            val idx = indexed.index
            val rev = ctrl.cmDataRevision

            td { textContent = "${idx + 1}" }
            td {
                addWidget(DoubleInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.x.toDouble() },
                    onChange = { ctrl.setSpawnField(idx) { e -> e.x = it.toFloat() } },
                    roundTo = 4,
                ))
            }
            td {
                addWidget(DoubleInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.y.toDouble() },
                    onChange = { ctrl.setSpawnField(idx) { e -> e.y = it.toFloat() } },
                    roundTo = 4,
                ))
            }
            td {
                addWidget(DoubleInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.z.toDouble() },
                    onChange = { ctrl.setSpawnField(idx) { e -> e.z = it.toFloat() } },
                    roundTo = 4,
                ))
            }
            td {
                addWidget(IntInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.angleX },
                    onChange = { ctrl.setSpawnField(idx) { e -> e.angleX = it } },
                ))
            }
            td {
                addWidget(IntInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.angleY },
                    onChange = { ctrl.setSpawnField(idx) { e -> e.angleY = it } },
                ))
            }
            td {
                addWidget(IntInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.angleZ },
                    onChange = { ctrl.setSpawnField(idx) { e -> e.angleZ = it } },
                ))
            }
            td {
                addWidget(IntInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.unknownA9.toInt() and 0xFFFF },
                    onChange = { ctrl.setSpawnField(idx) { e -> e.unknownA9 = it.toShort() } },
                    min = 0,
                    max = 65535,
                ))
            }
            td {
                addWidget(IntInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.unknownA10.toInt() and 0xFFFF },
                    onChange = { ctrl.setSpawnField(idx) { e -> e.unknownA10 = it.toShort() } },
                    min = 0,
                    max = 65535,
                ))
            }
            td {
                addWidget(Button(
                    enabled = ctrl.enabled,
                    iconLeft = Icon.Remove,
                    tooltip = cell("Delete this entry"),
                    onClick = { ctrl.deleteSpawnEntry(idx) },
                ))
            }
        }
}

private class ConfigPoolEntryRowWidget(
    private val ctrl: MonsterRandomnessController,
    private val indexed: IndexedConfigPoolEntry,
) : Widget() {
    override fun Node.createElement() =
        tr {
            val entry = indexed.entry
            val displayIndex = indexed.displayIndex
            val rev = ctrl.cmDataRevision

            td { textContent = "${displayIndex + 1}" }
            td {
                addWidget(DoubleInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.param1.toDouble() },
                    onChange = { ctrl.setConfigPoolField(indexed) { e -> e.param1 = it.toFloat() } },
                    roundTo = 4,
                ))
            }
            td {
                addWidget(DoubleInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.param2.toDouble() },
                    onChange = { ctrl.setConfigPoolField(indexed) { e -> e.param2 = it.toFloat() } },
                    roundTo = 4,
                ))
            }
            td {
                addWidget(DoubleInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.param3.toDouble() },
                    onChange = { ctrl.setConfigPoolField(indexed) { e -> e.param3 = it.toFloat() } },
                    roundTo = 4,
                ))
            }
            td {
                addWidget(DoubleInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.param4.toDouble() },
                    onChange = { ctrl.setConfigPoolField(indexed) { e -> e.param4 = it.toFloat() } },
                    roundTo = 4,
                ))
            }
            td {
                addWidget(DoubleInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.param5.toDouble() },
                    onChange = { ctrl.setConfigPoolField(indexed) { e -> e.param5 = it.toFloat() } },
                    roundTo = 4,
                ))
            }
            td {
                addWidget(IntInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.param7.toInt() },
                    onChange = { ctrl.setConfigPoolField(indexed) { e -> e.param7 = it.toShort() } },
                ))
            }
            td {
                addWidget(IntInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.param6.toInt() },
                    onChange = { ctrl.setConfigPoolField(indexed) { e -> e.param6 = it.toShort() } },
                ))
            }
            td {
                addWidget(IntInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.entryIndex.toInt() and 0xFFFF },
                    onChange = { ctrl.setConfigPoolEntryIndex(indexed, it) },
                    min = 0,
                    max = 65535,
                ))
            }
            td {
                addWidget(IntInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.unknown.toInt() and 0xFFFF },
                    onChange = { ctrl.setConfigPoolField(indexed) { e -> e.unknown = it.toShort() } },
                    min = 0,
                    max = 65535,
                ))
            }
            td {
                addWidget(IntInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.minChildren.toInt() and 0xFFFF },
                    onChange = { ctrl.setConfigPoolField(indexed) { e -> e.minChildren = it.toShort() } },
                    min = 0,
                    max = 65535,
                ))
            }
            td {
                addWidget(IntInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.maxChildren.toInt() and 0xFFFF },
                    onChange = { ctrl.setConfigPoolField(indexed) { e -> e.maxChildren = it.toShort() } },
                    min = 0,
                    max = 65535,
                ))
            }
            td {
                addWidget(Button(
                    enabled = ctrl.enabled,
                    iconLeft = Icon.Remove,
                    tooltip = cell("Delete this entry"),
                    onClick = { ctrl.deleteConfigPoolEntry(indexed) },
                ))
            }
        }
}

private class RoomItemWidget(
    private val ctrl: MonsterRandomnessController,
    private val room: RoomInfo,
    private val index: Int,
) : Widget() {
    override fun Node.createElement() =
        div {
            className = "pw-quest-editor-mr-room-item"

            toggleClass("pw-selected", ctrl.selectedRoomIndex.map { it == index })

            onclick = { ctrl.selectRoom(index) }

            span { textContent = "Room " }
            addWidget(IntInput(
                enabled = ctrl.enabled,
                value = ctrl.cmDataRevision.map { room.roomId },
                onChange = { ctrl.setRoomId(room.globalIndex, it) },
                min = 0,
                max = 65535,
            ))
            span { textContent = " (${room.entryCount} entries)" }
        }
}

private class MappingEntryRowWidget(
    private val ctrl: MonsterRandomnessController,
    private val indexed: IndexedMappingEntry,
) : Widget() {
    override fun Node.createElement() =
        tr {
            val entry = indexed.entry
            val displayIndex = indexed.displayIndex
            val rev = ctrl.cmDataRevision

            td { textContent = "${displayIndex + 1}" }
            td {
                addWidget(ComboBox(
                    enabled = ctrl.enabled,
                    items = cell(ctrl.monsterTypeOptions),
                    itemToString = { "${it.name} - ${it.index}" },
                    selected = rev.map {
                        val typeIdx = entry.monsterTypeIndex.toInt() and 0xFF
                        ctrl.monsterTypeOptions.find { opt -> opt.index == typeIdx }
                    },
                    onSelect = { ctrl.setMappingField(indexed) { e -> e.monsterTypeIndex = it.index.toByte() } },
                ))
            }
            td {
                addWidget(IntInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.definitionIndex.toInt() and 0xFF },
                    onChange = { ctrl.setMappingField(indexed) { e -> e.definitionIndex = it.toByte() } },
                    min = 0,
                    max = 255,
                ))
            }
            td {
                addWidget(IntInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.weight.toInt() and 0xFF },
                    onChange = { ctrl.setMappingField(indexed) { e -> e.weight = it.toByte() } },
                    min = 0,
                    max = 255,
                ))
            }
            td {
                addWidget(IntInput(
                    enabled = ctrl.enabled,
                    value = rev.map { entry.unknown.toInt() and 0xFF },
                    onChange = { ctrl.setMappingField(indexed) { e -> e.unknown = it.toByte() } },
                    min = 0,
                    max = 255,
                ))
            }
            td {
                addWidget(Button(
                    enabled = ctrl.enabled,
                    iconLeft = Icon.Remove,
                    tooltip = cell("Delete this entry"),
                    onClick = { ctrl.deleteMappingEntry(indexed) },
                ))
            }
        }
}

private class SimulatedMonsterRowWidget(
    private val ctrl: MonsterRandomnessController,
    private val monster: world.phantasmal.psolib.fileFormats.quest.ChallengeModeSimulatedMonster,
    private val index: Int,
) : Widget() {
    override fun Node.createElement() =
        tr {
            td { textContent = "${index + 1}" }
            td { textContent = monster.sourceEventId.toString() }
            td { textContent = monster.waveNumber.toString() }
            td { textContent = monster.roomId.toString() }
            td { textContent = ctrl.simulatedMonsterName(monster) }
            td { textContent = monster.definitionIndex.toString() }
            td { textContent = monster.numChildren.toString() }
            td { textContent = monster.location.x.toString() }
            td { textContent = monster.location.y.toString() }
            td { textContent = monster.location.z.toString() }
        }
}
