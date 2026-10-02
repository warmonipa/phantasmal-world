package world.phantasmal.web.viewer.stores

import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.w3c.fetch.Response
import org.w3c.files.File
import world.phantasmal.core.externals.browser.FileSystemFileHandle
import world.phantasmal.cell.observe
import world.phantasmal.psolib.fileFormats.ninja.XvrTexture
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.shared.dto.SectionId
import world.phantasmal.web.test.WebTestContext
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.TestApplicationUrl
import world.phantasmal.web.core.PwToolType
import world.phantasmal.web.viewer.ViewerUrls
import world.phantasmal.web.viewer.controllers.ViewerToolbarController
import world.phantasmal.web.viewer.models.AnimationModel
import world.phantasmal.web.viewer.models.CharacterClass
import world.phantasmal.web.viewer.models.ViewerModel
import world.phantasmal.webui.files.FileHandle
import kotlin.js.Promise
import kotlin.test.*

class ViewerLoadingTests : WebTestSuite {
    @Test
    fun character_body_and_section_id_commit_only_the_latest_combination() = testAsync {
        val store = readyStore()
        // All appearance requests share the real character texture cache, but only the final
        // request is allowed to publish the combination selected while the cache was loading.
        withFetchGate("/player/HUmarTex.afs") { gate ->
            coroutineScope {
                val model = launch { store.setCurrentModel(ViewerModel.Character(CharacterClass.HUmar)) }
                gate.awaitStarted()
                val changes = mutableListOf<List<XvrTexture?>>()
                disposer.add(store.currentTextures.observe { changes.add(it.toList()) })
                val section = launch { store.setCurrentSectionId(SectionId.Redria) }
                val body = launch { store.setCurrentBody(2) }
                // Start the suspending selections before allowing their shared cache to complete.
                kotlinx.coroutines.yield()
                gate.release()
                model.join()
                section.join()
                body.join()
                val expected = components.characterClassAssetLoader.loadXvrTextures(
                    CharacterClass.HUmar, SectionId.Redria, 2,
                )
                assertEquals(expected, store.currentTextures.value)
                assertEquals(listOf(expected), changes)
            }
        }
    }

    @Test
    fun late_object_and_item_loads_cannot_replace_a_newer_npc() = testAsync {
        val store = readyStore()
        for ((model, path) in listOf(
            ViewerModel.Object(ObjectType.ChristmasTree) to "/objects/${ObjectType.ChristmasTree.typeId}.nj",
            ViewerModel.findBySlug("ItemModel_68")!! to "/items/ItemModelEp4.afs",
        )) {
            withFetchGate(path) { gate ->
                coroutineScope {
                    val oldLoad = launch { store.setCurrentModel(model) }
                    gate.awaitStarted()
                    store.setCurrentModel(ViewerModel.Npc(NpcType.RagRappy))
                    val geometry = store.currentNinjaGeometry.value
                    gate.release()
                    oldLoad.join()
                    assertSame(geometry, store.currentNinjaGeometry.value)
                }
            }
        }
    }

    @Test
    fun older_model_success_cannot_replace_the_latest_model() = testAsync {
        val store = readyStore()
        withFetchGate("/npcs/Hildebear.nj") { gate ->
            coroutineScope {
                val oldLoad = launch { store.setCurrentModel(ViewerModel.Npc(NpcType.Hildebear)) }
                gate.awaitStarted()
                store.setCurrentModel(ViewerModel.Npc(NpcType.RagRappy))
                val geometry = store.currentNinjaGeometry.value
                val textures = store.currentTextures.value.toList()
                gate.release()
                oldLoad.join()
                assertEquals(ViewerModel.Npc(NpcType.RagRappy), store.currentModel.value)
                assertSame(geometry, store.currentNinjaGeometry.value)
                assertEquals(textures, store.currentTextures.value)
            }
        }
    }

    @Test
    fun older_texture_failure_cannot_clear_the_latest_model_or_animation() = testAsync {
        val store = readyStore()
        withFetchGate("/npcs/Hildebear.xvm", fail = true) { gate ->
            coroutineScope {
                val oldLoad = launch { store.setCurrentModel(ViewerModel.Npc(NpcType.Hildebear)) }
                gate.awaitStarted()
                store.setCurrentModel(ViewerModel.Npc(NpcType.RagRappy))
                store.setCurrentAnimation(AnimationModel("Wait", "/npcs/RagRappy_wait.njm"))
                val geometry = store.currentNinjaGeometry.value
                val textures = store.currentTextures.value.toList()
                val motion = store.currentNinjaMotion.value
                gate.release()
                oldLoad.join()
                assertNotNull(motion)
                assertSame(geometry, store.currentNinjaGeometry.value)
                assertEquals(textures, store.currentTextures.value)
                assertSame(motion, store.currentNinjaMotion.value)
            }
        }
    }

    @Test
    fun ultimate_selection_owns_both_geometry_and_textures() = testAsync {
        val store = readyStore()
        withFetchGate("/npcs/Hildebear.nj") { gate ->
            coroutineScope {
                val oldLoad = launch { store.setCurrentModel(ViewerModel.Npc(NpcType.Hildebear)) }
                gate.awaitStarted()
                store.setUltimate(true)
                val geometry = store.currentNinjaGeometry.value
                val textures = store.currentTextures.value.toList()
                gate.release()
                oldLoad.join()
                assertSame(geometry, store.currentNinjaGeometry.value)
                assertEquals(textures, store.currentTextures.value)
            }
        }
    }

    @Test
    fun selecting_animation_during_model_load_is_preserved() = testAsync {
        val store = readyStore()
        withFetchGate("/npcs/Hildebear.nj") { gate ->
            coroutineScope {
                val modelLoad = launch { store.setCurrentModel(ViewerModel.Npc(NpcType.Hildebear)) }
                gate.awaitStarted()
                val animation = AnimationModel("Stand", "/npcs/Hildebear_stand.njm")
                store.setCurrentAnimation(animation)
                val motion = store.currentNinjaMotion.value
                gate.release()
                modelLoad.join()
                assertEquals(animation, store.currentAnimation.value)
                assertNotNull(motion)
                assertSame(motion, store.currentNinjaMotion.value)
            }
        }
    }

    @Test
    fun older_animation_success_and_failure_cannot_replace_newer_animation() = testAsync {
        val store = readyStore()
        for (fail in listOf(false, true)) {
            val old = AnimationModel("Old $fail", "/npcs/Hildebear_${if (fail) "walk" else "stand"}.njm")
            withFetchGate(old.filePath, fail) { gate ->
                coroutineScope {
                    val oldLoad = launch { store.setCurrentAnimation(old) }
                    gate.awaitStarted()
                    val animation = AnimationModel("Walk", "/npcs/Booma_walk.njm")
                    store.setCurrentAnimation(animation)
                    val motion = store.currentNinjaMotion.value
                    gate.release()
                    oldLoad.join()
                    assertEquals(animation, store.currentAnimation.value)
                    assertNotNull(motion)
                    assertSame(motion, store.currentNinjaMotion.value)
                }
            }
        }
    }

    @Test
    fun clearing_animation_invalidates_its_in_flight_load() = testAsync {
        val store = readyStore()
        withFetchGate("/npcs/Hildebear_stand.njm") { gate ->
            coroutineScope {
                val load = launch {
                    store.setCurrentAnimation(AnimationModel("Stand", "/npcs/Hildebear_stand.njm"))
                }
                gate.awaitStarted()
                store.setCurrentAnimation(null)
                gate.release()
                load.join()
                assertNull(store.currentAnimation.value)
                assertNull(store.currentNinjaMotion.value)
            }
        }
    }

    @Test
    fun clearing_geometry_invalidates_in_flight_model() = testAsync {
        val store = readyStore()
        withFetchGate("/npcs/Hildebear.nj") { gate ->
            coroutineScope {
                val load = launch { store.setCurrentModel(ViewerModel.Npc(NpcType.Hildebear)) }
                gate.awaitStarted()
                store.setCurrentNinjaGeometry(null)
                gate.release()
                load.join()
                assertNull(store.currentModel.value)
                assertNull(store.currentNinjaGeometry.value)
                assertEquals(emptyList(), store.currentTextures.value)
            }
        }
    }

    @Test
    fun local_file_load_cannot_override_a_later_model_selection() = testAsync {
        val store = readyStore()
        val controller = disposer.add(ViewerToolbarController(store))
        val data = components.assetLoader.loadArrayBuffer("/npcs/Hildebear.nj")
        val gate = FileGate(File(arrayOf(data), "Hildebear.nj"))
        coroutineScope {
            val load = launch { controller.openFiles(listOf(gate.handle)) }
            gate.started.await()
            store.setCurrentModel(ViewerModel.Npc(NpcType.RagRappy))
            val geometry = store.currentNinjaGeometry.value
            gate.release()
            load.join()
            assertEquals(ViewerModel.Npc(NpcType.RagRappy), store.currentModel.value)
            assertSame(geometry, store.currentNinjaGeometry.value)
        }
    }

    @Test
    fun local_textures_preserve_the_pending_model_geometry_even_if_old_textures_fail() = testAsync {
        val store = readyStore()
        for ((type, fail) in listOf(NpcType.Hildebear to false, NpcType.RagRappy to true)) {
            withFetchGate("/npcs/${type.name}.xvm", fail) { gate ->
                coroutineScope {
                    val load = launch { store.setCurrentModel(ViewerModel.Npc(type)) }
                    gate.awaitStarted()
                    val localTextures = components.npcAssetLoader.loadXvrTextures(NpcType.Booma)
                    store.setCurrentTextures(localTextures)
                    gate.release()
                    load.join()
                    assertSame(
                        components.npcAssetLoader.loadNinjaObject(type),
                        assertIs<NinjaGeometry.Object>(store.currentNinjaGeometry.value).obj,
                    )
                    assertEquals(localTextures, store.currentTextures.value)
                }
            }
        }
    }

    @Test
    fun newer_local_file_import_supersedes_an_older_import() = testAsync {
        val store = readyStore()
        val controller = disposer.add(ViewerToolbarController(store))
        val oldData = components.assetLoader.loadArrayBuffer("/npcs/Hildebear.nj")
        val newData = components.assetLoader.loadArrayBuffer("/npcs/RagRappy.nj")
        val gate = FileGate(File(arrayOf(oldData), "old.nj"))
        coroutineScope {
            val load = launch { controller.openFiles(listOf(gate.handle)) }
            gate.started.await()
            controller.openFiles(listOf(FileHandle.Simple(File(arrayOf(newData), "new.nj"))))
            val geometry = store.currentNinjaGeometry.value
            gate.release()
            load.join()
            assertSame(geometry, store.currentNinjaGeometry.value)
        }
    }

    @Test
    fun local_motion_supersedes_pending_animation_and_clears_its_selection() = testAsync {
        val store = readyStore()
        withFetchGate("/npcs/Hildebear_stand.njm") { gate ->
            coroutineScope {
                val load = launch {
                    store.setCurrentAnimation(AnimationModel("Stand", "/npcs/Hildebear_stand.njm"))
                }
                gate.awaitStarted()
                val localMotion = components.animationAssetLoader.loadAnimation("/npcs/Booma_walk.njm")
                store.setCurrentNinjaMotion(localMotion)
                gate.release()
                load.join()
                assertNull(store.currentAnimation.value)
                assertSame(localMotion, store.currentNinjaMotion.value)
            }
        }
    }

    @Test
    fun pending_model_does_not_commit_after_store_disposal() = testAsync {
        // Own this store explicitly so the test disposer does not dispose it a second time.
        val store = ViewerStore(
            components.characterClassAssetLoader, components.npcAssetLoader,
            components.itemAssetLoader, components.objectAssetLoader,
            components.animationAssetLoader, components.uiStore,
        )
        store.setCurrentModel(ViewerModel.Npc(NpcType.Booma))
        withFetchGate("/npcs/Hildebear.nj") { gate ->
            coroutineScope {
                val load = launch { store.setCurrentModel(ViewerModel.Npc(NpcType.Hildebear)) }
                gate.awaitStarted()
                val geometry = store.currentNinjaGeometry.value
                store.dispose()
                gate.release()
                load.join()
                assertSame(geometry, store.currentNinjaGeometry.value)
            }
        }
    }

    private suspend fun WebTestContext.readyStore(): ViewerStore {
        components.applicationUrl = TestApplicationUrl(
            "/${PwToolType.Viewer.slug}${ViewerUrls.mesh}?model=Booma",
        )
        return components.viewerStore.also { it.setCurrentModel(ViewerModel.Npc(NpcType.Booma)) }
    }

    private suspend fun withFetchGate(
        path: String,
        fail: Boolean = false,
        block: suspend (FetchGate) -> Unit,
    ) {
        val gate = FetchGate(path, fail)
        try {
            block(gate)
        } finally {
            gate.close()
        }
    }

    /** Delays the actual browser response; loaders and parsers still run against real assets. */
    private class FetchGate(path: String, fail: Boolean) {
        private val originalFetch: dynamic = window.asDynamic().fetch
        private val started = CompletableDeferred<Unit>()
        private var complete: () -> Unit = {}

        init {
            window.asDynamic().fetch = { input: dynamic, init: dynamic ->
                val response = originalFetch.call(window, input, init).unsafeCast<Promise<Response>>()
                if ((input as String).endsWith(path)) {
                    Promise<Response> { resolve, reject ->
                        response.then { value ->
                            complete = {
                                if (fail) reject(Exception("Controlled load failure.")) else resolve(value)
                            }
                            started.complete(Unit)
                            Unit
                        }.catch { error -> reject(error) }
                    }
                } else {
                    response
                }
            }
        }

        suspend fun awaitStarted() = withTimeout(10_000) { started.await() }
        fun release() = complete()
        fun close() {
            window.asDynamic().fetch = originalFetch
            release()
        }
    }

    private class FileGate(file: File) {
        val started = CompletableDeferred<Unit>()
        private var complete: () -> Unit = {}
        val handle = FileHandle.System(js("({})").unsafeCast<FileSystemFileHandle>().also { native ->
            native.asDynamic().name = file.name
            native.asDynamic().getFile = {
                Promise<File> { resolve, _ ->
                    complete = { resolve(file) }
                    started.complete(Unit)
                }
            }
        })

        fun release() = complete()
    }
}
