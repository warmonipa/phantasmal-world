package world.phantasmal.web.questEditor.loading

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import world.phantasmal.core.externals.browser.FileSystemDirectoryHandle
import world.phantasmal.core.externals.browser.arrayBuffer
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.asm.BytecodeIr
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.fileFormats.quest.*
import world.phantasmal.web.core.loading.AssetLoader
import world.phantasmal.web.core.loading.GslArchive
import world.phantasmal.web.core.loading.LoadingCache
import world.phantasmal.webui.DisposableContainer

class QuestLoader(private val assetLoader: AssetLoader) : DisposableContainer() {
    private val cache = addDisposable(
        LoadingCache<String, ArrayBuffer>(
            { path -> assetLoader.loadArrayBuffer("/quests$path") },
            { /* Nothing to dispose. */ }
        )
    )

    /**
     * Read a file from the data directory, falling back to data.gsl if the loose file is not found.
     */
    private suspend fun readDataFile(
        dataDir: FileSystemDirectoryHandle,
        gsl: GslArchive?,
        fileName: String,
    ): ArrayBuffer? {
        // Try loose file first (Ephinea overrides).
        try {
            return dataDir.getFileHandle(fileName).await()
                .getFile().await().arrayBuffer().await()
        } catch (_: Throwable) {
            // Fall through to GSL.
        }
        // Fall back to data.gsl archive.
        return gsl?.readFile(fileName)
    }

    suspend fun loadDefaultQuest(episode: Episode): Quest {
        val ver = episode.toInt()
        return loadQuest("/defaults/default_ep_$ver.qst")
    }

    suspend fun loadCityQuest(episode: Episode): Quest {
        val ver = episode.toInt()
        return loadQuest("/city/city_ep_$ver.qst")
    }

    suspend fun loadLobbyQuest(variant: Int, objectData: Buffer? = null): Quest {
        val definition = requireNotNull(getLobbyVariant(variant)) {
            "Unknown lobby number $variant."
        }
        // All lobby entries use the same editor quest shell. The floor mapping and object DAT
        // below select the actual Ephinea lobby.
        val quest = loadQuest("/lobby/lobby_01.qst")
        quest.name = "Lobby ${variant.toString().padStart(2, '0')}"
        quest.floorMappings = listOf(
            FloorMapping(
                floorId = LOBBY_FLOOR_ID,
                mapId = LOBBY_FLOOR_ID,
                mapAreaId = LOBBY_FLOOR_ID,
                mapVariation = variant,
            )
        )

        val lobbyObjectData = objectData ?: Buffer.fromArrayBuffer(
            assetLoader.loadArrayBuffer("/quests/lobby/data/${definition.datFileName}"),
            Endianness.Little,
        )

        val dat = parseDat(
            synthesizeDat(
                listOf(
                    DatFloorSection(
                        floorId = LOBBY_FLOOR_ID,
                        objData = lobbyObjectData,
                        npcData = Buffer.withSize(0, Endianness.Little),
                    )
                )
            )
        )
        quest.objects.clear()
        quest.objects.addAll(dat.objs.map { QuestObject(it.floorId, it.data) })

        return quest
    }

    /**
     * Load a free roam quest from the user's data directory (with data.gsl fallback).
     *
     * Loads per-floor dat (obj + npc + evt) files and the bin script for the area.
     * Works for all episodes and all area types (city, field).
     */
    suspend fun loadFreeRoamQuest(
        gameDirHandle: FileSystemDirectoryHandle,
        info: FreeRoamAreaInfo,
        selectedV1: Int = 0,
        selectedV2: Int = 0,
    ): FreeRoamResult {
        val episode = info.episode

        // Support both: user picked game root (has data/ subdir) or data/ directly.
        val dataDir = try {
            gameDirHandle.getDirectoryHandle("data").await()
        } catch (_: Throwable) {
            gameDirHandle
        }

        // Open data.gsl for fallback reads.
        val gsl = GslArchive.open(dataDir)

        // Load the bin file.
        val (binBuf, binName) = findBinFile(dataDir, gsl, info)

        // Try to load V3 GC `SetDataTable*.rel` first — it tells us the exact filenames per
        // (area, layout, entities). If present we're definitely on a V3 disc and use the
        // rel-driven path; otherwise probe between BB and V3 naming styles.
        val relTable = loadSetDataTableRel(dataDir, info)
        val datStyle = if (relTable != null) DatFilenameStyle.V3
            else detectDatStyle(dataDir, gsl, info)

        // Offline city dat files have a "_s" suffix before the extension (BB only — V3 has no `_s`).
        val offlineSuffix = if (datStyle == DatFilenameStyle.BB && info.isCity && info.offline) "_s" else ""

        // Load per-floor dat/evt files.
        val (sections, datFilesByFloor) = loadFloorSections(
            dataDir, gsl, info, offlineSuffix, selectedV1, selectedV2, datStyle, relTable,
        )

        val datCursor = synthesizeDat(sections)

        val quest = if (binBuf != null) {
            val shiftJis = binName?.substringBeforeLast('.')?.endsWith("_j") == true
            val q = parseBinDatToQuest(
                binCursor = binBuf.cursor(Endianness.Little),
                datCursor = datCursor,
                lenient = true,
                compressed = false,
                shiftJis = shiftJis,
            ).unwrap()

            q.episode = episode
            // Free roam bin files may have invalid language values; clamp to 0.
            if (q.language < 0) q.language = 0
            for (npc in q.npcs) { npc.episode = episode }

            // Always use our floor mappings — the bin's map_designate may not match
            // the floor range we loaded.
            q.floorMappings = buildFloorMappings(episode, info.floorRange, selectedV1)
            q
        } else {
            val dat = parseDat(datCursor)
            val objects = dat.objs.mapTo(mutableListOf()) { QuestObject(it.floorId, it.data) }
            val npcs = dat.npcs.mapTo(mutableListOf()) { QuestNpc(episode, it.floorId, it.data) }

            Quest(
                id = 0,
                language = 0,
                name = "Free Roam",
                shortDescription = "",
                longDescription = "",
                episode = episode,
                objects = objects,
                npcs = npcs,
                events = dat.events,
                datUnknowns = dat.unknowns,
                bytecodeIr = BytecodeIr(emptyList()),
                shopItems = uintArrayOf(),
                floorMappings = buildFloorMappings(episode, info.floorRange, selectedV1),
            )
        }

        return FreeRoamResult(quest, binName, datFilesByFloor)
    }

    /**
     * Loads per-floor dat (obj + npc) and evt files for each floor in the area's floor range.
     * Returns the list of [DatFloorSection]s and a map of floorId to (objDatName, npcDatName, evtName).
     *
     * On V3 GC there is no separate `.evt` file — events are inline in the object/npc dat,
     * so [DatFloorSection.eventData] is left empty and the third triple element is "".
     */
    private suspend fun loadFloorSections(
        dataDir: FileSystemDirectoryHandle,
        gsl: GslArchive?,
        info: FreeRoamAreaInfo,
        offlineSuffix: String,
        selectedV1: Int,
        selectedV2: Int,
        datStyle: DatFilenameStyle,
        relTable: SetDataTableRel?,
    ): Pair<List<DatFloorSection>, Map<Int, Triple<String, String, String>>> {
        val sections = mutableListOf<DatFloorSection>()
        val datFilesByFloor = mutableMapOf<Int, Triple<String, String, String>>()

        for (floor in info.floorRange) {
            val floorInfo = getFloorFileInfo(info.episode, floor) ?: continue

            val v1 = resolveVariant(selectedV1, floorInfo.v1Values)
            val v2 = resolveVariant(selectedV2, floorInfo.v2Values)

            val objCandidates: List<String>
            val npcCandidates: List<String>
            val evtName: String
            val relEntry = relTable?.get(floor, v1, v2)
            if (relEntry != null) {
                // V3 GC REL-driven path. On this disc:
                //   `<setup>d.dat`  = primary entity list (main data)
                //   `<setup>ad.dat` = holiday-decoration overlay used only during seasonal
                //                     events (Halloween / Easter / Christmas — see
                //                     `bm_ene_lappy_ap_hw.bml` etc. on the same disc).
                // Both files share the 68-byte stride and the same entity-type numbering;
                // most areas have byte-identical d/ad (no decoration), only a few differ.
                //
                // We load `d.dat` only — treating `ad.dat` as if it were a BB-style 72-byte
                // NPC array caused misalignment and rendered Halloween entities on top of
                // the main scene. NPCs come either from this list (by type ID) or from
                // `npc_act` calls in the bin script.
                objCandidates = listOf("${relEntry.areaSetupBasename}d.dat")
                npcCandidates = emptyList()
                evtName = ""
            } else {
                objCandidates = datCandidates(floorInfo, v1, v2, DatFileType.OBJECTS, datStyle, offlineSuffix)
                npcCandidates = datCandidates(floorInfo, v1, v2, DatFileType.ENEMIES, datStyle, offlineSuffix)
                evtName = if (datStyle == DatFilenameStyle.BB)
                    resolveDatFilename(floorInfo, v1, v2, DatFileType.EVENTS, datStyle)
                else ""
            }

            val (objDatName, objBuf) = readFirstExisting(dataDir, gsl, objCandidates)
            val (npcDatName, npcBuf) = readFirstExisting(dataDir, gsl, npcCandidates)
            val evtBuf = if (evtName.isNotEmpty()) readDataFile(dataDir, gsl, evtName) else null

            sections.add(
                DatFloorSection(
                    floorId = floor,
                    objData = objBuf?.let { Buffer.fromArrayBuffer(it, Endianness.Little) }
                        ?: Buffer.withSize(0, Endianness.Little),
                    npcData = npcBuf?.let { Buffer.fromArrayBuffer(it, Endianness.Little) }
                        ?: Buffer.withSize(0, Endianness.Little),
                    eventData = evtBuf?.let { Buffer.fromArrayBuffer(it, Endianness.Little) }
                        ?: Buffer.withSize(0, Endianness.Little),
                )
            )

            datFilesByFloor[floor] = Triple(objDatName, npcDatName, evtName)
        }

        return Pair(sections, datFilesByFloor)
    }

    /**
     * Try to load the appropriate `SetDataTable*.rel` for this area's mode (online/offline,
     * normal/ultimate). Returns null if no rel is found — caller falls back to filename probing.
     *
     * The rel is big-endian (GC PowerPC) and contains per-(area, layout, entities) basenames.
     */
    private suspend fun loadSetDataTableRel(
        dataDir: FileSystemDirectoryHandle,
        info: FreeRoamAreaInfo,
    ): SetDataTableRel? {
        val candidates = buildList {
            val ulti = if (info.ultimate) "Ulti" else ""
            val onOff = if (info.offline) "Off" else "On"
            // Primary: matches mode (e.g., SetDataTableOnUlti.rel)
            add("SetDataTable${onOff}${ulti}.rel")
            // Non-ultimate as fallback
            if (ulti.isNotEmpty()) add("SetDataTable${onOff}.rel")
            // Online as last resort
            if (info.offline) add("SetDataTableOn.rel")
        }
        for (name in candidates) {
            val buf = readDataFile(dataDir, null, name) ?: continue
            return try {
                val beBuf = Buffer.fromArrayBuffer(buf, Endianness.Big)
                parseSetDataTableRel(beBuf.cursor())
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    /**
     * Probe the data directory to determine which dat-filename style it uses.
     * Tries BB-style first; falls back to V3-style. Defaults to BB if nothing matches.
     */
    private suspend fun detectDatStyle(
        dataDir: FileSystemDirectoryHandle,
        gsl: GslArchive?,
        info: FreeRoamAreaInfo,
    ): DatFilenameStyle {
        for (floor in info.floorRange) {
            val floorInfo = getFloorFileInfo(info.episode, floor) ?: continue
            val v1 = floorInfo.v1Values.firstOrNull() ?: 0
            val v2 = floorInfo.v2Values.firstOrNull() ?: 0
            for (name in datCandidates(floorInfo, v1, v2, DatFileType.OBJECTS, DatFilenameStyle.BB, "")) {
                if (readDataFile(dataDir, gsl, name) != null) return DatFilenameStyle.BB
            }
            for (name in datCandidates(floorInfo, v1, v2, DatFileType.OBJECTS, DatFilenameStyle.V3, "")) {
                if (readDataFile(dataDir, gsl, name) != null) return DatFilenameStyle.V3
            }
        }
        return DatFilenameStyle.BB
    }

    /**
     * Build ordered list of candidate filenames for a given floor/style/type.
     *
     * V3 GC discs sometimes drop the variant indices when only one variant exists
     * (e.g., `map_forest01d.dat` instead of `map_forest01_00d.dat`). We try the
     * variant-numbered form first, then fall back to a bare form using a synthetic
     * [FloorFileInfo] with empty v1/v2 lists.
     */
    private fun datCandidates(
        floorInfo: FloorFileInfo,
        v1: Int,
        v2: Int,
        type: DatFileType,
        style: DatFilenameStyle,
        offlineSuffix: String,
    ): List<String> {
        val primary = resolveDatFilename(floorInfo, v1, v2, type, style)
        val candidates = mutableListOf(primary)
        if (style == DatFilenameStyle.V3 &&
            (floorInfo.v1Values.isNotEmpty() || floorInfo.v2Values.isNotEmpty())) {
            // Fall back to bare token (no _v1_v2) — discs with stripped variants.
            val bareInfo = FloorFileInfo(floorInfo.token, emptyList(), emptyList())
            candidates += resolveDatFilename(bareInfo, v1, v2, type, style)
        }
        return if (offlineSuffix.isEmpty()) candidates
        else candidates.map { it.replace(".dat", "${offlineSuffix}.dat") }
    }

    /**
     * Try each candidate filename in order; return the first that exists with its bytes.
     * If none exist, returns the primary name with null buffer.
     */
    private suspend fun readFirstExisting(
        dataDir: FileSystemDirectoryHandle,
        gsl: GslArchive?,
        candidates: List<String>,
    ): Pair<String, ArrayBuffer?> {
        for (name in candidates) {
            val buf = readDataFile(dataDir, gsl, name)
            if (buf != null) return name to buf
        }
        return (candidates.firstOrNull() ?: "") to null
    }

    /**
     * Find the bin file for a free roam area. Tries loose files first, then data.gsl.
     */
    private suspend fun findBinFile(
        dataDir: FileSystemDirectoryHandle,
        gsl: GslArchive?,
        info: FreeRoamAreaInfo,
    ): Pair<ArrayBuffer?, String?> {
        val binPrefix = info.binPrefix ?: return Pair(null, null)
        val ult = if (info.ultimate) "_u" else ""

        if (info.isCity) {
            val onOff = if (info.offline) "off" else "on"
            for (lang in BIN_LANG_SUFFIXES) {
                val candidate = "${binPrefix}_${onOff}_${lang}${ult}.bin"
                val buf = readDataFile(dataDir, gsl, candidate)
                if (buf != null) return Pair(buf, candidate)
            }
        } else {
            for (lang in BIN_LANG_SUFFIXES) {
                val candidate = "${binPrefix}_${lang}${ult}.bin"
                val buf = readDataFile(dataDir, gsl, candidate)
                if (buf != null) return Pair(buf, candidate)
                // Also try without ultimate suffix if ult is set.
                if (ult.isNotEmpty()) {
                    val nonUlt = "${binPrefix}_${lang}.bin"
                    val buf2 = readDataFile(dataDir, gsl, nonUlt)
                    if (buf2 != null) return Pair(buf2, nonUlt)
                }
            }
        }

        return Pair(null, null)
    }

    private fun buildFloorMappings(
        episode: Episode,
        floorRange: IntRange,
        selectedV1: Int,
    ): List<FloorMapping> {
        return floorRange.mapNotNull { floor ->
            val mapId = getMapId(episode, floor) ?: return@mapNotNull null
            val floorInfo = getFloorFileInfo(episode, floor)
            val v1 = resolveVariant(selectedV1, floorInfo?.v1Values)
            FloorMapping(floor, mapId, floor, v1)
        }
    }

    private fun resolveVariant(selected: Int, available: List<Int>?): Int =
        if (available != null && selected in available) selected
        else available?.firstOrNull() ?: 0

    private suspend fun loadQuest(path: String): Quest =
        parseQstToQuest(cache.get(path).cursor(Endianness.Little)).unwrap().quest
}
