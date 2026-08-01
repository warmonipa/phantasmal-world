package world.phantasmal.web.questEditor.rendering

import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.cell.Cell
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.web.core.rendering.DisposableThreeRenderer
import world.phantasmal.web.core.rendering.Renderer
import world.phantasmal.web.externals.three.PerspectiveCamera
import world.phantasmal.web.questEditor.asm.SymbolChatTriggerInfo
import world.phantasmal.web.questEditor.loading.AreaAssetLoader
import world.phantasmal.web.questEditor.loading.EntityAssetLoader
import world.phantasmal.web.questEditor.loading.FogAssetLoader
import world.phantasmal.web.questEditor.loading.ParticleAssetLoader
import world.phantasmal.web.questEditor.loading.SymbolChatColliRepository
import world.phantasmal.web.questEditor.rendering.input.QuestInputManager
import world.phantasmal.web.questEditor.stores.*

class QuestRenderer(
    areaAssetLoader: AreaAssetLoader,
    entityAssetLoader: EntityAssetLoader,
    particleAssetLoader: ParticleAssetLoader,
    fogAssetLoader: FogAssetLoader,
    questEditorStore: QuestEditorStore,
    questEditorUiStore: QuestEditorUiStore,
    playbackVisualizationStore: PlaybackVisualizationStore,
    viewportStore: ViewportStore,
    areaStore: AreaStore,
    symbolChatColliRepository: SymbolChatColliRepository,
    symbolChatTriggers: Cell<List<SymbolChatTriggerInfo>>,
    readSegmentData: (Int) -> Buffer?,
    onNavigateToScriptLabel: (Int) -> Unit,
    onActivateEventsWidget: () -> Unit,
    createThreeRenderer: (HTMLCanvasElement) -> DisposableThreeRenderer,
) : Renderer() {
    override val context = addDisposable(QuestRenderContext(
        createCanvas(),
        PerspectiveCamera(
            fov = 45.0,
            aspect = 1.0,
            near = 10.0,
            far = 5_000.0,
        ),
    ))

    override val disposableThreeRenderer = addDisposable(createThreeRenderer(context.canvas))

    override val inputManager = addDisposable(QuestInputManager(
        questEditorStore,
        questEditorUiStore,
        viewportStore,
        context,
        onNavigateToScriptLabel,
        onActivateEventsWidget,
    ))

    private val meshManager = addDisposable(
        QuestEditorMeshManager(
            areaAssetLoader,
            entityAssetLoader,
            particleAssetLoader,
            questEditorStore,
            questEditorUiStore,
            playbackVisualizationStore,
            viewportStore,
            areaStore,
            context,
            symbolChatColliRepository,
            symbolChatTriggers,
            readSegmentData,
        ),
    )
    private val fogPreviewManager = addDisposable(
        FogPreviewManager(context, fogAssetLoader, questEditorStore)
    )

    init {

        var prevQuest = questEditorStore.currentQuest.value
        var prevAreaVariant = questEditorStore.currentAreaVariant.value

        observeNow(questEditorStore.currentQuest, questEditorStore.currentAreaVariant) { q, av ->
            if (q !== prevQuest || av !== prevAreaVariant) {
                inputManager.resetCamera()

                prevQuest = q
                prevAreaVariant = av
            }
        }
    }

    override fun render() {
        fogPreviewManager.beforeRender()
        meshManager.beforeRender()
        super.render()
    }
}
