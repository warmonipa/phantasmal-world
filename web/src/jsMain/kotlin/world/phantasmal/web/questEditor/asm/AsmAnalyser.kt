package world.phantasmal.web.questEditor.asm

import kotlinx.atomicfu.atomic
import kotlinx.browser.window
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.w3c.dom.Worker
import world.phantasmal.cell.Cell
import world.phantasmal.cell.MutableCell
import world.phantasmal.cell.list.ListCell
import world.phantasmal.cell.list.mutableListCell
import world.phantasmal.cell.mutableCell
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.web.core.observable.Emitter
import world.phantasmal.web.core.observable.Observable
import world.phantasmal.web.shared.JSON_FORMAT
import world.phantasmal.web.shared.messages.*
import world.phantasmal.web.shared.messages.Label
import world.phantasmal.web.shared.messages.RegisterInfo
import world.phantasmal.web.shared.messages.SegmentInfo
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

private val logger = KotlinLogging.logger {}

data class FloorMappingsUpdate(
    val generation: Int,
    val floorMappings: List<FloorMapping>,
    val scriptEpisode: Episode,
)

class AsmAnalyser {
    private var _floorMappings: MutableCell<List<FloorMapping>> = mutableCell(emptyList())
    private val _floorMappingsUpdates = Emitter<FloorMappingsUpdate>()
    private val _problems = mutableListCell<AssemblyProblem>()
    private val _labels = mutableListCell<Label>()
    private val _registers = mutableListCell<RegisterInfo>()
    private val _segments = mutableListCell<SegmentInfo>()

    private val worker = Worker(workerUrl())

    companion object {
        private fun workerUrl(): String {
            val pathname = window.location.pathname
            val basePath =
                if (pathname.endsWith(".html")) {
                    pathname.substring(0, pathname.lastIndexOf('/'))
                } else {
                    pathname.removeSuffix("/")
                }
            return "$basePath/assembly-worker.js"
        }
    }
    private var nextRequestId = atomic(0)
    private var usedFloorIds: Set<Int> = emptySet()
    private var datEntryFloorIds: Map<Int, Set<Int>> = emptyMap()

    /**
     * Maps request IDs to continuations.
     */
    private val inFlightRequests = mutableMapOf<Int, CancellableContinuation<*>>()

    val floorMappings: Cell<List<FloorMapping>> = _floorMappings
    /** Non-deduplicating stream of revision-tagged floor-mapping notifications from the worker. */
    val floorMappingsUpdates: Observable<FloorMappingsUpdate> = _floorMappingsUpdates
    val problems: ListCell<AssemblyProblem> = _problems
    val labels: ListCell<Label> = _labels
    val registers: ListCell<RegisterInfo> = _registers
    val segments: ListCell<SegmentInfo> = _segments

    init {
        worker.onmessage = { e ->
            val json = e.data as String
            receiveMessage(JSON_FORMAT.decodeFromString(json))
        }
    }

    /**
     * Identifies the current script and DAT context. Notifications about replaced inputs can
     * still arrive and are dropped, so they can't update the current document.
     */
    internal var asmGeneration = 0
        private set

    fun setAsm(
        asm: List<String>,
        usedFloorIds: Set<Int> = emptySet(),
        version: Version = Version.BB_V4,
        datEntryFloorIds: Map<Int, Set<Int>> = emptyMap(),
    ): Int {
        asmGeneration++
        this.usedFloorIds = usedFloorIds
        this.datEntryFloorIds = datEntryFloorIds
        _problems.clear()
        _labels.clear()
        sendMessage(ClientNotification.SetAsm(asm, asmGeneration, usedFloorIds, version, datEntryFloorIds))
        return asmGeneration
    }

    fun updateAsm(changes: List<AsmChange>): Int {
        asmGeneration++
        sendMessage(ClientNotification.UpdateAsm(changes, asmGeneration))
        return asmGeneration
    }

    fun updateUsedFloorIds(
        usedFloorIds: Set<Int>,
        datEntryFloorIds: Map<Int, Set<Int>> = this.datEntryFloorIds,
    ) {
        if (this.usedFloorIds == usedFloorIds && this.datEntryFloorIds == datEntryFloorIds) return
        this.usedFloorIds = usedFloorIds
        this.datEntryFloorIds = datEntryFloorIds
        asmGeneration++
        sendMessage(ClientNotification.UpdateUsedFloorIds(usedFloorIds, asmGeneration, datEntryFloorIds))
    }

    suspend fun getCompletions(lineNo: Int, col: Int): List<CompletionItem> =
        sendRequest { id -> Request.GetCompletions(id, lineNo, col) }

    suspend fun getSignatureHelp(lineNo: Int, col: Int): SignatureHelp? =
        sendRequest { id -> Request.GetSignatureHelp(id, lineNo, col) }

    suspend fun getHover(lineNo: Int, col: Int): Hover? =
        sendRequest { id -> Request.GetHover(id, lineNo, col) }

    suspend fun getDefinition(lineNo: Int, col: Int): List<AsmRange> =
        sendRequest { id -> Request.GetDefinition(id, lineNo, col) }

    suspend fun getLabels(): List<Label> =
        sendRequest { id -> Request.GetLabels(id) }

    suspend fun getHighlights(lineNo: Int, col: Int): List<AsmRange> =
        sendRequest { id -> Request.GetHighlights(id, lineNo, col) }

    private suspend fun <T> sendRequest(createRequest: (id: Int) -> Request): T {
        val id = nextRequestId.getAndIncrement()

        try {
            return withTimeout(5_000) {
                suspendCancellableCoroutine { cont ->
                    // Store continuation and resume it when we receive a response.
                    inFlightRequests[id] = cont
                    sendMessage(createRequest(id))
                }
            }
        } catch (e: TimeoutCancellationException) {
            inFlightRequests.remove(id)
            throw e
        }
    }

    private fun sendMessage(message: ClientMessage) {
        worker.postMessage(JSON_FORMAT.encodeToString(message))
    }

    internal fun receiveMessage(message: ServerMessage) {
        if (message is ServerNotification && message.generation != asmGeneration) return

        when (message) {
            is ServerNotification.FloorMappings -> {
                _floorMappings.value = message.floorMappings
                _floorMappingsUpdates.emit(
                    FloorMappingsUpdate(message.generation, message.floorMappings, message.scriptEpisode)
                )
            }

            is ServerNotification.Problems -> {
                _problems.value = message.problems
            }

            is ServerNotification.Labels -> {
                _labels.value = message.labels
            }

            is ServerNotification.Registers -> {
                _registers.value = message.registers
            }

            is ServerNotification.Segments -> {
                _segments.value = message.segments
            }

            is Response<*> -> {
                val continuation = inFlightRequests.remove(message.id)

                if (continuation == null) {
                    logger.warn {
                        "No continuation for ${message::class.simpleName} ${message.id}, possibly due to timeout."
                    }
                } else {
                    continuation.unsafeCast<Continuation<Any>>()
                        .resume(message.result.unsafeCast<Any>())
                }
            }
        }
    }
}
