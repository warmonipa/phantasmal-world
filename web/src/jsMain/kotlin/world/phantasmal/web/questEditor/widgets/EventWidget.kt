package world.phantasmal.web.questEditor.widgets

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import world.phantasmal.cell.map
import world.phantasmal.web.questEditor.controllers.EventsController
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.webui.dom.*
import world.phantasmal.webui.obj
import world.phantasmal.webui.widgets.Dropdown
import world.phantasmal.webui.widgets.IntInput
import world.phantasmal.webui.widgets.Widget

class EventWidget(
    private val ctrl: EventsController,
    private val event: QuestEventModel,
) : Widget() {
    private val isSelected = ctrl.isSelected(event)
    private val isMultiSelected = ctrl.isMultiSelected(event)
    private val npcsSummary = ctrl.getEventNpcsSummary(event)

    override fun Node.createElement() =
        div {
            className = "pw-quest-editor-event"
            toggleClass("pw-selected", isSelected)
            toggleClass("pw-multi-selected", isMultiSelected)
            tabIndex = 0

            onclick = { e ->
                e.stopPropagation()
                // Check for multi-select modifier key: Ctrl on Windows/Linux, Cmd on macOS
                val isMultiSelectKey = e.ctrlKey || e.metaKey

                if (isMultiSelectKey) {
                    ctrl.selectEvent(event, true)
                } else {
                    ctrl.selectEvent(event, false)

                    // Navigate to event's section with delayed execution to avoid cell dependency issues
                    window.setTimeout({
                        ctrl.goToEventSection(event)
                    }, 100)
                }
            }

            onkeyup = { e ->
                if ((e.target as? Element)?.nodeName != "INPUT") {
                    when (e.key) {
                        "Enter" -> ctrl.selectEvent(event)
                        "Delete" -> ctrl.removeEvent(event)
                    }
                }
            }

            observeNow(isSelected) {
                if (it) {
                    scrollIntoView(obj<ScrollIntoViewOptions> {
                        behavior = ScrollBehavior.SMOOTH
                        inline = ScrollLogicalPosition.NEAREST
                        block = ScrollLogicalPosition.NEAREST
                    })
                }
            }

            div {
                className = "pw-quest-editor-event-props"

                table {
                    tr {
                        val idInput = IntInput(
                            enabled = ctrl.enabled,
                            value = event.id,
                            onChange = { ctrl.setId(event, it) },
                            label = "ID:",
                            min = 0,
                            step = 1,
                        )
                        th { addChild(idInput.label!!) }
                        td { addChild(idInput) }
                    }
                    tr {
                        val sectionIdInput = IntInput(
                            enabled = ctrl.enabled,
                            value = event.sectionId,
                            onChange = { ctrl.setSectionId(event, it) },
                            label = "Section:",
                            min = 0,
                            step = 1,
                        )
                        th { addChild(sectionIdInput.label!!) }
                        td { addChild(sectionIdInput) }
                    }
                    tr {
                        val waveInput = IntInput(
                            enabled = ctrl.enabled,
                            value = event.wave.map { it.id },
                            onChange = { ctrl.setWaveId(event, it) },
                            label = "Wave:",
                            min = 1,
                            step = 1,
                        )
                        th { addChild(waveInput.label!!) }
                        td { addChild(waveInput) }
                    }
                    tr {
                        val delayInput = IntInput(
                            enabled = ctrl.enabled,
                            value = event.delay,
                            onChange = { ctrl.setDelay(event, it) },
                            label = "Delay:",
                            min = 0,
                            step = 1,
                        )
                        th { addChild(delayInput.label!!) }
                        td { addChild(delayInput) }
                    }
                }

                // Monster icon with hover overlay
                span {
                    className = "pw-quest-editor-event-monster-icon"
                    textContent = "\uD83D\uDC7E" // Monster emoji

                    // Create overlay element
                    val overlay = (document.createElement("div") as HTMLDivElement).apply {
                        className = "pw-quest-editor-event-monster-overlay"
                    }
                    document.body?.appendChild(overlay)

                    // Update overlay content when NPC summary changes
                    observeNow(npcsSummary) { summary ->
                        if (summary != null) {
                            style.display = "inline-block"
                            overlay.textContent = summary
                        } else {
                            style.display = "none"
                        }
                    }

                    // Show/hide overlay on hover
                    onmouseenter = { _ ->
                        val rect = getBoundingClientRect()
                        overlay.style.left = "${rect.left}px"
                        overlay.style.display = "block"

                        // Check if overlay would go below screen bottom
                        val overlayHeight = overlay.offsetHeight
                        val viewportHeight = window.innerHeight
                        val spaceBelow = viewportHeight - rect.bottom - 4

                        if (overlayHeight > spaceBelow) {
                            // Show above the icon
                            overlay.style.top = "${rect.top - overlayHeight - 4}px"
                        } else {
                            // Show below the icon
                            overlay.style.top = "${rect.bottom + 4}px"
                        }
                    }

                    onmouseleave = {
                        overlay.style.display = "none"
                    }

                    // Clean up overlay when widget is disposed
                    addDisposable(world.phantasmal.core.disposable.disposable {
                        overlay.remove()
                    })
                }
            }
            div {
                className = "pw-quest-editor-event-actions"

                table {
                    thead {
                        tr {
                            th {
                                colSpan = 3
                                textContent = "Actions:"
                            }
                        }
                    }
                    tbody {
                        bindChildWidgetsTo(event.actions) { action, _ ->
                            EventActionWidget(ctrl, event, action)
                        }
                    }
                    tfoot {
                        tr {
                            td {
                                colSpan = 3
                                addWidget(Dropdown(
                                    enabled = ctrl.enabled,
                                    text = "Add action",
                                    items = ctrl.eventActionTypes,
                                    onSelect = { ctrl.addAction(event, it) }
                                ))
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
            style(
                """
                .pw-quest-editor-event {
                    display: flex;
                    flex-wrap: wrap;
                    border: var(--pw-border);
                    margin: 2px;
                    background-color: hsl(0, 0%, 17%);
                    outline: none;
                }

                .pw-quest-editor-event:hover, .pw-quest-editor-event:focus {
                    border-color: hsl(0, 0%, 30%);
                    background-color: hsl(0, 0%, 20%);
                    color: hsl(0, 0%, 85%);
                }

                .pw-quest-editor-event.pw-selected {
                    border-color: hsl(0, 0%, 35%);
                    background-color: hsl(0, 0%, 25%);
                    color: hsl(0, 0%, 90%);
                }

                .pw-quest-editor-event.pw-multi-selected {
                    border-color: hsl(200, 100%, 50%);
                    background-color: hsl(200, 50%, 20%);
                    color: hsl(0, 0%, 90%);
                    box-shadow: 0 0 3px hsl(200, 100%, 50%);
                }

                .pw-quest-editor-event.pw-selected.pw-multi-selected {
                    border-color: hsl(200, 100%, 60%);
                    background-color: hsl(200, 50%, 25%);
                    box-shadow: 0 0 5px hsl(200, 100%, 60%);
                }
                
                .pw-quest-editor-event-props, .pw-quest-editor-event-actions {
                    padding: 2px 6px;
                }
                
                .pw-quest-editor-event-props {
                    width: 115px;
                }
                
                .pw-quest-editor-event-actions {
                    width: 165px;
                }
                
                .pw-quest-editor-event > div > table {
                    width: 100%;
                    border-collapse: collapse;
                }

                .pw-quest-editor-event th {
                    text-align: left;
                }

                /* Monster icon */
                .pw-quest-editor-event-monster-icon {
                    display: none;
                    cursor: pointer;
                    font-size: 14px;
                    margin-top: 4px;
                    padding: 2px 4px;
                    border-radius: 3px;
                    background-color: hsl(0, 0%, 25%);
                }

                .pw-quest-editor-event-monster-icon:hover {
                    background-color: hsl(0, 0%, 35%);
                }

                /* Monster overlay (fixed position, appended to body) */
                .pw-quest-editor-event-monster-overlay {
                    display: none;
                    position: fixed;
                    background-color: hsl(0, 0%, 10%);
                    color: hsl(0, 0%, 90%);
                    padding: 8px 12px;
                    border-radius: 4px;
                    border: 1px solid hsl(0, 0%, 30%);
                    font-size: 12px;
                    font-family: monospace;
                    white-space: pre;
                    z-index: 10000;
                    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.5);
                    pointer-events: none;
                }
                """.trimIndent()
            )
        }
    }
}
