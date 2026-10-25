package world.phantasmal.web.questEditor.loading

import kotlinx.browser.window
import world.phantasmal.core.Success
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.quest.*
import world.phantasmal.psolib.fileFormats.ninja.*
import world.phantasmal.web.core.loading.AssetLoader
import world.phantasmal.web.core.loading.NPCS_WITH_ULTIMATE_SKIN
import world.phantasmal.web.core.models.*
import world.phantasmal.web.core.rendering.conversion.ninjaObjectToMesh
import world.phantasmal.web.core.rendering.disposeObject3DResources
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.questEditor.stores.AreaStore
import world.phantasmal.web.shared.dto.SectionId
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.viewer.loading.*
import world.phantasmal.web.viewer.models.*
import kotlin.test.Test

/** Diagnostic harness, installed temporarily by run.py. Findings are data, not passing regressions. */
class AssetCompletenessAuditTests : WebTestSuite {
    private val assets = AssetLoader(basePath = "/assets")

    private suspend fun audit(id: String, block: suspend () -> String) {
        val original = window.asDynamic().fetch
        val requests: dynamic = js("[]")
        val wrap: dynamic = js("(function(original, requests) { return function(url, options) { return original.call(window, url, options).then(function(r) { requests.push({path: String(url), status:r.status}); return r; }); }; })")
        window.asDynamic().fetch = wrap(original, requests)
        var status = "OK"
        val detail = try { block() } catch (e: Throwable) {
            status = "ERROR"
            e.message ?: e.toString()
        } finally { window.asDynamic().fetch = original }
        val row: dynamic = js("({})")
        row.id = id
        row.status = status
        row.detail = detail
        row.requests = requests
        // Emit a separate browser console event; stdout concatenation can exceed
        // the Gradle TeamCity line parser's buffer on the expanded corpus.
        window.asDynamic().console.log("ASSET_AUDIT " + JSON.stringify(row))
    }

    private fun inspect(mesh: Mesh): String {
        val positions = mesh.geometry.asDynamic().attributes.position
        check(positions != null && (positions.count as Int) > 0) { "Empty geometry" }
        val count = positions.count as Int
        val materials = mesh.material
        val list: dynamic = js("Array.isArray(materials) ? materials : [materials]")
        var textured = 0
        for (i in 0 until (list.length as Int)) if (list[i].map != null) textured++
        return "vertices=$count;materials=${list.length};textured=$textured"
    }

    private fun convert(obj: NinjaObject<*, *>, textures: List<XvrTexture?>): String {
        val invalid = missingTextureSlots(obj, textures)
        val mesh = ninjaObjectToMesh(obj, textures)
        return try {
            inspect(mesh) + ";missing_texture_slots=${invalid.joinToString(",")};textures=${textures.size}"
        } finally { disposeObject3DResources(mesh) }
    }

    private fun missingTextureSlots(obj: NinjaObject<*, *>, textures: List<XvrTexture?>): List<Int> {
        val indices = mutableSetOf<Int>()
        fun visit(node: NinjaObject<*, *>) {
            if (!node.evaluationFlags.hidden) when (val model = node.model) {
                is NjModel -> model.meshes.mapNotNullTo(indices) { it.textureId }
                is XjModel -> model.meshes.mapNotNullTo(indices) { it.material.textureId }
                else -> Unit
            }
            if (!node.evaluationFlags.breakChildTrace) node.children.forEach(::visit)
        }
        visit(obj)
        return indices.filter { textures.getOrNull(it) == null }.sorted()
    }

    @Test fun editor_objects() = testAsync {
        val variants = listOf<Pair<ObjectType, Int?>>(/* CORPUS_VARIANTS */)
        val cases = (ObjectType.entries.map { type ->
            val obj = QuestObject(type, 0)
            type to if (obj.modelOffset >= 0) obj.model else null
        } + variants + (0..3).map { ObjectType.LittleCryotube to it }).distinct()
        for ((type, model) in cases) audit("editor-object/${type.name}/${model ?: "base"}") {
            val visual = objectVisualClass(type)
            if (visual != ObjectVisualClass.StaticModel) return@audit "excluded=$visual"
            val loader = EntityAssetLoader(assets)
            try {
                val mesh = loader.loadInstancedMesh(type, model)
                try {
                    check(mesh.name == type.uniqueName) { "Default cylinder substituted" }
                    inspect(mesh)
                } finally { disposeObject3DResources(mesh) }
            } finally { loader.dispose() }
        }
    }

    @Test fun editor_npcs() = testAsync {
        for (type in NpcType.entries) for (ult in listOf(false, true)) {
            audit("editor-npc/${type.name}/$ult") {
                if (type == NpcType.Unknown || type == NpcType.NpcEnemy) return@audit "excluded=NoModelMapping"
                val loader = EntityAssetLoader(assets)
                try {
                    val mesh = loader.loadInstancedMesh(type, null, ultimate = ult)
                    try {
                        check(mesh.name == type.uniqueName) { "Default cylinder substituted" }
                        inspect(mesh)
                    } finally { disposeObject3DResources(mesh) }
                } finally { loader.dispose() }
            }
        }
    }

    @Test fun viewer_objects() = testAsync {
        for (entry in ViewerModel.ALL.filterIsInstance<ViewerModel.Object>()) {
            audit("viewer-object/${entry.objectType.name}") {
                val loader = ObjectAssetLoader(assets)
                try { convert(loader.loadNinjaObject(entry.objectType), loader.loadXvrTextures(entry.objectType)) }
                finally { loader.dispose() }
            }
        }
    }

    @Test fun viewer_npcs_and_animations() = testAsync {
        val animations = mutableSetOf<String>()
        for (entry in ViewerModel.ALL.filterIsInstance<ViewerModel.Npc>()) {
            val type = entry.npcType
            for (ult in if (type in NPCS_WITH_ULTIMATE_SKIN) listOf(false, true) else listOf(false)) {
                audit("viewer-npc/${type.name}/$ult") {
                    val loader = NpcAssetLoader(assets)
                    try { convert(loader.loadNinjaObject(type, ult), loader.loadXvrTextures(type, ult)) }
                    finally { loader.dispose() }
                }
            }
            animations.addAll(NpcAssetLoader.getAnimations(type).map { it.filePath })
        }
        for (path in animations.sorted()) audit("animation/$path") {
            val loader = AnimationAssetLoader(assets)
            try { loader.loadAnimation(path); "parsed" } finally { loader.dispose() }
        }
    }

    @Test fun viewer_items() = testAsync {
        val loader = ItemAssetLoader(assets)
        try {
            for (entry in ViewerModel.ALL.filterIsInstance<ViewerModel.Item>().distinctBy { it.index to it.textureIndex }) {
                audit("item/${entry.index}/${entry.textureIndex}") {
                    convert(loader.loadNinjaObject(entry.index), loader.loadXvrTextures(entry.textureIndex))
                }
            }
        } finally { loader.dispose() }
    }

    @Test fun source_item_pairs() = testAsync {
        val loader = ItemAssetLoader(assets)
        try {
            for ((model, texture) in listOf<Pair<Int, Int>>(/* SOURCE_ITEM_PAIRS */)) {
                audit("source-item/$model/$texture") {
                    convert(loader.loadNinjaObject(model), loader.loadXvrTextures(texture))
                }
            }
        } finally { loader.dispose() }
    }

    @Test fun player_animations() = testAsync {
        val loader = AnimationAssetLoader(assets)
        try {
            for (i in 0 until 572) audit("player-animation/$i") {
                loader.loadAnimation("/player/animation/animation_${i.toString().padStart(3, '0')}.njm")
                "parsed"
            }
        } finally { loader.dispose() }
    }

    @Test fun standalone_textures() = testAsync {
        for (path in listOf<String>(/* XVM_PATHS */)) audit("standalone-texture/$path") {
            val result = parseXvm(assets.loadArrayBuffer(path).cursor(Endianness.Little))
            check(result is Success) { "XVM parse failed: $result" }
            check(result.value.textures.isNotEmpty()) { "Empty XVM" }
            "textures=${result.value.textures.size}"
        }
    }

    @Test fun characters() = testAsync {
        for (char in CharacterClass.entries) {
            val loader = CharacterClassAssetLoader(assets)
            try {
                for (head in 0 until char.headStyleCount) for (hair in 0 until maxOf(1, char.hairStyleCount)) {
                    audit("character/${char.name}/head=$head/hair=$hair") {
                        convert(loader.loadNinjaObject(char, head, hair), loader.loadXvrTextures(char, SectionId.VALUES[0], 0, 0, 0, head))
                    }
                }
                for (body in 0 until char.bodyStyleCount) for (section in SectionId.VALUES) {
                    audit("character-textures/${char.name}/body=$body/section=${section.name}") {
                        val textures = loader.loadXvrTextures(char, section, body)
                        "textures=${textures.size};null_slots=${textures.indices.filter { textures[it] == null }}"
                    }
                }
                val cast = char.hairStyleCount == 0
                for (skin in 0 until if (cast) 25 else 4) for (face in 0 until if (cast) 1 else 5) {
                    for (head in 0 until char.headStyleCount) audit("character-skin-face/${char.name}/$skin/$face/$head") {
                        val textures = loader.loadXvrTextures(char, SectionId.Viridia, 0, skin, face, head)
                        "textures=${textures.size};null_slots=${textures.indices.filter { textures[it] == null }}"
                    }
                }
            } finally { loader.dispose() }
        }
        for (i in 0 until CharacterClassAssetLoader.NPC_MODEL_COUNT) audit("npc-player/$i") {
            val loader = CharacterClassAssetLoader(assets)
            try {
                val parts = loader.loadNpcParts(i)
                val textures = loader.loadNpcXvrTextures(i)
                parts.joinToString("|") { convert(it, textures) }
            } finally { loader.dispose() }
        }
    }

    private suspend fun areas(episode: Episode, areaId: Int) {
        val lookup = AreaAssetLoader(assets)
        val store = AreaStore(lookup)
        val area = store.getArea(episode, areaId)!!
        store.dispose()
        lookup.dispose()
        for (variant in area.areaVariants) for (ult in if (episode == Episode.I) listOf(false, true) else listOf(false)) {
            audit("area/${episode.name}/$areaId/${variant.id}/$ult") {
                val loader = AreaAssetLoader(assets)
                try {
                    val render = loader.loadRenderGeometry(episode, variant, ult)
                    val collision = loader.loadCollisionGeometry(episode, variant, ult)
                    "render_children=${render.children.size};collision_children=${collision.children.size}"
                } finally { loader.dispose() }
            }
        }
    }

    /* AREA_TESTS */

    @Test fun effects() = testAsync {
        audit("fog") { "entries=${FogAssetLoader(assets).load().size}" }
        val loader = ParticleAssetLoader(assets)
        try {
            for (map in 0..46) audit("particle/$map") {
                val result = loader.load(map)
                "global=${result.globalEffects.size};map=${result.mapEffects.size};textures=${result.texturesById.size}"
            }
        } finally { loader.dispose() }
    }
}
