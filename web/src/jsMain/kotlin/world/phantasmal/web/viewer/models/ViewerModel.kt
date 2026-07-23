package world.phantasmal.web.viewer.models

import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.displayName

sealed class ViewerModel {
    abstract val uiName: String
    abstract val slug: String

    /**
     * Label honoring the Ultimate-difficulty rename for NPCs (e.g. Sinow Beat → Sinow Blue).
     * Everything else — and NPCs without a distinct Ultimate name — falls back to [uiName].
     */
    fun displayName(ultimate: Boolean): String =
        if (this is Npc) npcType.displayName(ultimate) else uiName

    data class Character(val characterClass: CharacterClass) : ViewerModel() {
        override val uiName: String = characterClass.uiName
        override val slug: String = characterClass.slug
    }

    data class Npc(val npcType: NpcType) : ViewerModel() {
        override val uiName: String = npcType.uniqueName
        override val slug: String = npcType.name
    }

    data class Object(val objectType: ObjectType) : ViewerModel() {
        override val uiName: String = objectType.uniqueName
        override val slug: String = "Object_${objectType.name}"
    }

    data class Item(
        val index: Int,
        private val displayName: String,
        val kind: ItemKind,
        val textureIndex: Int = index,
        val itemTypeId: Int? = null,
    ) : ViewerModel() {
        override val uiName: String = displayName
        override val slug: String = when {
            itemTypeId != null ->
                "Weapon_${itemTypeId.toString(16).padStart(6, '0').uppercase()}"
            textureIndex == index ->
                "ItemModel_$index"
            else ->
                "ItemModel_${index}_Texture_$textureIndex"
        }
    }

    enum class ItemKind {
        Weapon,
        Mag,
        Barrier,
        Tool,
        Other,
    }

    data class Group(val label: String, val items: List<ViewerModel>)

    data class Category(val label: String, val groups: List<Group>) {
        val count: Int = groups.sumOf { it.items.size }
    }

    companion object {
        val CHARACTERS: List<ViewerModel> = CharacterClass.VALUES_LIST.map(::Character)

        val EP1_ENEMIES: List<ViewerModel> = listOf(
            NpcType.Hildebear,
            NpcType.Hildeblue,
            NpcType.RagRappy,
            NpcType.AlRappy,
            NpcType.Monest,
            NpcType.Mothmant,
            NpcType.SavageWolf,
            NpcType.BarbarousWolf,
            NpcType.Booma,
            NpcType.Gobooma,
            NpcType.Gigobooma,
            NpcType.GrassAssassin,
            NpcType.PoisonLily,
            NpcType.NarLily,
            NpcType.EvilShark,
            NpcType.PalShark,
            NpcType.GuilShark,
            NpcType.PanArms,
            NpcType.Dubchic,
            NpcType.Gilchic,
            NpcType.Garanz,
            NpcType.SinowBeat,
            NpcType.SinowGold,
            NpcType.Canadine,
            NpcType.Canane,
            NpcType.Delsaber,
            NpcType.ChaosSorcerer,
            NpcType.DarkGunner,
            NpcType.DarkBelra,
            NpcType.Bulclaw,
            NpcType.Claw,
            NpcType.Dimenian,
            NpcType.LaDimenian,
            NpcType.SoDimenian,
            NpcType.ChaosBringer,
            NpcType.PofuillySlime,
            NpcType.PouillySlime,
        ).map(::Npc)

        val EP2_ENEMIES: List<ViewerModel> = listOf(
            NpcType.Merillia,
            NpcType.Meriltas,
            NpcType.Mericarol,
            NpcType.Merikle,
            NpcType.Mericus,
            NpcType.UlGibbon,
            NpcType.ZolGibbon,
            NpcType.Gibbles,
            NpcType.Gee,
            NpcType.GiGue,
            NpcType.IllGill,
            NpcType.DelLily,
            NpcType.Epsilon,
            NpcType.SinowBerill,
            NpcType.SinowSpigell,
            NpcType.Dolmolm,
            NpcType.Dolmdarl,
            NpcType.Morfos,
            NpcType.Recobox,
            NpcType.Delbiter,
            NpcType.SinowZoa,
            NpcType.SinowZele,
            NpcType.Deldepth,
        ).map(::Npc)

        val EP4_ENEMIES: List<ViewerModel> = listOf(
            NpcType.Boota,
            NpcType.ZeBoota,
            NpcType.BaBoota,
            NpcType.Dorphon,
            NpcType.DorphonEclair,
            NpcType.Goran,
            NpcType.PyroGoran,
            NpcType.GoranDetonator,
            NpcType.SandRappy,
            NpcType.DelRappy,
            NpcType.Astark,
            NpcType.SatelliteLizard,
            NpcType.Yowie,
            NpcType.MerissaA,
            NpcType.MerissaAA,
            NpcType.Girtablulu,
            NpcType.Zu,
            NpcType.Pazuzu,
        ).map(::Npc)

        val BOSSES: List<ViewerModel> = listOf(
            // EP1
            NpcType.Dragon,
            NpcType.DeRolLe,
            NpcType.VolOptPart1,
            NpcType.VolOptPart2,
            NpcType.DarkFalz,
            // EP2
            NpcType.BarbaRay,
            NpcType.GalGryphon,
            NpcType.OlgaFlow,
            // EP4
            NpcType.SaintMilion,
            NpcType.Shambertin,
            NpcType.Kondrieu,
            NpcType.GolDragon,
        ).map(::Npc)

        private val OBJECTS_WITHOUT_VIEWER_ASSET: Set<ObjectType> = setOf(
            ObjectType.Unknown,
            ObjectType.PlayerSet,
            ObjectType.Particle,
            ObjectType.LightCollision,
            ObjectType.EnvSound,
            ObjectType.FogCollision,
            ObjectType.EventCollision,
            ObjectType.CharaCollision,
            ObjectType.ObjRoomID,
            ObjectType.LensFlare,
            ObjectType.ScriptCollision,
            ObjectType.MapCollision,
            ObjectType.ScriptCollisionA,
            ObjectType.ItemLight,
            ObjectType.RadarCollision,
            ObjectType.FogCollisionSW,
            ObjectType.ImageBoard,
            ObjectType.StarLight2D,
            ObjectType.LensFlare2,
            ObjectType.RadarHideCollision,
            ObjectType.MenuActivation,
            ObjectType.BoxDetectObject,
            ObjectType.SymbolChatObject,
            ObjectType.TouchPlateObject,
            ObjectType.TargetableObject,
            ObjectType.EffectObject,
            ObjectType.CountDownObject,
            ObjectType.ChatSensor,
            ObjectType.RadarIcon,
            ObjectType.EnvSoundEx,
            ObjectType.EnvSoundGlobal,
            ObjectType.TelepipeLocation,
            ObjectType.BGMCollision,
            ObjectType.Pioneer2InvisibleTouchplate,
            ObjectType.TempleMapDetect,
            ObjectType.Firework,
            ObjectType.MainRagolTeleporterBattleInNextArea,
            ObjectType.Rainbow,
            ObjectType.FloatingBlueLight,
            ObjectType.PopupTrapNoTech,
            ObjectType.Poison,
            ObjectType.EnemyTypeBoxYellow,
            ObjectType.EnemyTypeBoxBlue,
            ObjectType.EmptyTypeBoxBlue,
            ObjectType.FloatingSoul,
            ObjectType.Butterfly,
            ObjectType.Camera,
            ObjectType.CcaAreaTeleporter,
            ObjectType.LightningController,
            ObjectType.WhiteBird,
            ObjectType.OrangeBird,
            ObjectType.BiwaMushi,
            ObjectType.JungleDesign,
            ObjectType.Seagull,
            ObjectType.Ep2Particle,
            ObjectType.WarpInBarbaRayRoom,
            ObjectType.LiveCamera,
            ObjectType.InstaWarp,
            ObjectType.LabInvisibleObject,
            ObjectType.AreaWarpEndingJung,
            ObjectType.Ep4LightSource,
            ObjectType.BreakableBrownRock,
            ObjectType.UnknownItem897,
            ObjectType.UnknownItem898,
            ObjectType.OozingDesertPlant,
            ObjectType.UnknownItem901,
            ObjectType.UnknownItem903,
            ObjectType.UnknownItem904,
            ObjectType.UnknownItem905,
            ObjectType.UnknownItem906,
            ObjectType.DesertPlantHasCollision,
            ObjectType.Ep4TestDoor,
            ObjectType.Ep4TestParticle,
            ObjectType.Heat,
            ObjectType.TopOfSaintMillionEgg,
            ObjectType.Ep4BossRockSpawner,
            ObjectType.UnknownItem16,
            ObjectType.Battery,
            ObjectType.LobbyGameMenu,
            ObjectType.GBAStation,
            ObjectType.UnknownItem832,
            ObjectType.UnknownItem833,
        )

        val OBJECTS: List<ViewerModel> = ObjectType.entries
            .filter(::objectTypeHasViewerAsset)
            .map(::Object)

        val OBJECT_GROUPS: List<Group> = OBJECTS
            .filterIsInstance<Object>()
            .groupBy { objectGroupLabel(it.objectType) }
            .map { (label, items) -> Group(label, items.sortedBy { it.objectType.typeId }) }

        private val DUPLICATE_WEAPON_NAMES: Set<String> =
            VIEWER_WEAPON_CATALOG
                .groupingBy { it.name }
                .eachCount()
                .filterValues { it > 1 }
                .keys

        val WEAPONS: List<ViewerModel> =
            VIEWER_WEAPON_CATALOG.map(::weaponCatalogItem)

        /**
         * Raw weapon-model URLs are kept for old links and model-by-model visual verification.
         * The visible weapon catalog above is item-based, so aliases such as the TypeXX series
         * remain separate even when they reuse the same model and texture.
         */
        private val LEGACY_WEAPON_MODELS: List<ViewerModel> = buildList {
            val textureVariants = weaponTextureVariants()
            for (index in 0 until MAG_MODEL_OFFSET) {
                add(itemModel(index))
                addAll(textureVariants.filter { it.index == index })
            }
        }

        val ITEMS: List<ViewerModel> = buildList {
            addAll(WEAPONS)
            for (index in MAG_MODEL_OFFSET until ITEM_MODEL_COUNT) {
                add(itemModel(index))
            }
        }

        val ITEM_GROUPS: List<Group> = ITEMS
            .filterIsInstance<Item>()
            .groupBy { it.kind }
            .map { (kind, items) ->
                Group(itemGroupLabel(kind), items)
            }

        val ALL: List<ViewerModel> =
            CHARACTERS + EP1_ENEMIES + EP2_ENEMIES + EP4_ENEMIES + BOSSES + ITEMS + OBJECTS

        private val CHARACTER_GROUPS: List<Group> = listOf(
            Group("Characters", CHARACTERS),
        )

        private val ENEMY_GROUPS: List<Group> = listOf(
            Group("EP1 Enemies", EP1_ENEMIES),
            Group("EP2 Enemies", EP2_ENEMIES),
            Group("EP4 Enemies", EP4_ENEMIES),
        )

        private val BOSS_GROUPS: List<Group> = listOf(
            Group("Bosses", BOSSES),
        )

        val CATEGORIES: List<Category> = listOf(
            Category("Characters", CHARACTER_GROUPS),
            Category("Enemies", ENEMY_GROUPS),
            Category("Bosses", BOSS_GROUPS),
            Category("Items", ITEM_GROUPS),
            Category("Objects", OBJECT_GROUPS),
        )

        val GROUPS: List<Group> = CATEGORIES.flatMap { it.groups }

        fun findBySlug(slug: String): ViewerModel? =
            ALL.find { it.slug == slug }
                ?: LEGACY_WEAPON_MODELS.find { it.slug == slug }

        private fun objectGroupLabel(type: ObjectType): String {
            val typeId = type.typeId?.toInt() ?: return "Objects - Other"
            return when (typeId) {
                in 0..63 -> "Objects - Common"
                in 64..87 -> "Objects - Pioneer 2"
                in 128..151 -> "Objects - Forest"
                in 192..225 -> "Objects - Caves"
                in 256..268 -> "Objects - Mines"
                in 304..372 -> "Objects - Ruins"
                in 384..396 -> "Objects - Lobby"
                in 400..403, 448 -> "Objects - Spaceship"
                in 416..427 -> "Objects - Temple"
                in 512..531 -> "Objects - CCA"
                in 544..553 -> "Objects - Seabed"
                in 576..701 -> "Objects - Episode II"
                in 768..961 -> "Objects - Episode IV"
                else -> "Objects - Other"
            }
        }

        private fun objectTypeHasViewerAsset(type: ObjectType): Boolean =
            type.typeId != null && type !in OBJECTS_WITHOUT_VIEWER_ASSET

        private const val ITEM_MODEL_COUNT = 408
        private const val MAG_MODEL_OFFSET = 271
        private const val BARRIER_MODEL_OFFSET = 295
        private const val TOOL_MODEL_OFFSET = 362
        private const val OTHER_ITEM_MODEL_OFFSET = 368

        // TODO: Investigate the material rendering issue on item type 0x001600
        // (Akiko's Frying Pan, model/texture 21).
        private fun weaponCatalogItem(entry: ViewerWeaponCatalogEntry): Item {
            val itemTypeId = entry.itemTypeId.toString(16).padStart(6, '0').uppercase()
            val displayName =
                if (entry.name in DUPLICATE_WEAPON_NAMES) "${entry.name} [$itemTypeId]"
                else entry.name

            return Item(
                index = entry.modelIndex,
                displayName = displayName,
                kind = ItemKind.Weapon,
                textureIndex = entry.textureIndex,
                itemTypeId = entry.itemTypeId,
            )
        }

        /**
         * ItemPMT weapon `type` selects geometry while `skin` selects textures. Most weapons use
         * the same value for both, but these visual variants reuse another weapon's geometry.
         */
        private fun weaponTextureVariants(): List<Item> =
            listOf(
                weaponTextureVariant(12, 272, "Silence Claw"),
                weaponTextureVariant(12, 273, "Nei's Claw"),
                weaponTextureVariant(12, 294, "TypeSL/Claw, TypeKN/Claw, TypeCL/Claw"),
                weaponTextureVariant(
                    15,
                    271,
                    "Agito (1975, 1977, 1980, 1983, 1991, 2001)",
                ),
                weaponTextureVariant(15, 292, "Raikiri"),
                weaponTextureVariant(20, 290, "Burning Visit"),
                weaponTextureVariant(46, 288, "Black King Bar"),
                weaponTextureVariant(68, 275, "Snow Queen"),
                weaponTextureVariant(77, 291, "Iron Faust"),
                weaponTextureVariant(85, 287, "Fatsia"),
                weaponTextureVariant(108, 289, "Power Maser"),
                weaponTextureVariant(109, 293, "LOGiN"),
            )

        private fun weaponTextureVariant(
            modelIndex: Int,
            textureIndex: Int,
            name: String,
        ): Item {
            val modelId = modelIndex.toString().padStart(3, '0')
            val textureId = textureIndex.toString().padStart(3, '0')
            return Item(
                modelIndex,
                "Weapon $modelId/$textureId - $name",
                ItemKind.Weapon,
                textureIndex,
            )
        }

        private fun itemModel(index: Int): Item {
            val id = index.toString().padStart(3, '0')

            return when {
                index < MAG_MODEL_OFFSET -> {
                    val name = weaponModelLabel(index)
                    Item(index, "Weapon $id - $name", ItemKind.Weapon)
                }

                index < BARRIER_MODEL_OFFSET -> {
                    val skin = index - MAG_MODEL_OFFSET
                    Item(index, "Mag $id - skin $skin", ItemKind.Mag)
                }

                index < TOOL_MODEL_OFFSET -> {
                    val skin = index - BARRIER_MODEL_OFFSET
                    val name = barrierModelLabel(skin)
                    Item(index, "Barrier $id - $name", ItemKind.Barrier)
                }

                index < OTHER_ITEM_MODEL_OFFSET -> {
                    val skin = index - TOOL_MODEL_OFFSET
                    val name = toolModelLabel(skin)
                    Item(index, "Tool $id - $name", ItemKind.Tool)
                }

                else -> Item(index, "Item Model $id", ItemKind.Other)
            }
        }

        private fun itemGroupLabel(kind: ItemKind): String =
            when (kind) {
                ItemKind.Weapon -> "Item Models - Weapons"
                ItemKind.Mag -> "Item Models - Mags"
                ItemKind.Barrier -> "Item Models - Barriers"
                ItemKind.Tool -> "Item Models - Tools"
                ItemKind.Other -> "Item Models - Other"
            }

        private fun weaponModelLabel(skin: Int): String =
            when (skin) {
                0 -> "Saber family"
                1 -> "Sword family"
                2 -> "Dagger family"
                3 -> "Partisan family"
                4 -> "Slicer family"
                5 -> "Handgun family"
                6 -> "Rifle family"
                7 -> "Mechgun family"
                8 -> "Shot family"
                9 -> "Cane family"
                10 -> "Rod family"
                11 -> "Wand family"
                12 -> "Photon Claw"
                13 -> "Double Saber"
                14 -> "Sonic Knuckle"
                15 -> "Orotiagito"
                16 -> "Soul Eater"
                17 -> "Spread Needle"
                18 -> "Holy Ray"
                19 -> "Inferno Bazooka"
                20 -> "Flame Visit"
                21 -> "Akiko's Frying Pan"
                22 -> "Sorcerer's Cane"
                23 -> "S-Beat's Blade"
                24 -> "P-Arms's Blade"
                25 -> "Delsaber's Buster"
                26 -> "Bringer's Rifle"
                27 -> "Egg Blaster"
                28 -> "Psycho Wand"
                29 -> "Heaven Punisher"
                30 -> "Lavis Cannon"
                31 -> "Victor Axe"
                32 -> "Chain Sawd"
                33 -> "Caduceus"
                34 -> "Sting Tip"
                35 -> "Magical Piece"
                36 -> "Technical Crozier"
                37 -> "Suppressed Gun"
                38 -> "Ancient Saber"
                39 -> "Harisen Battle Fan"
                40 -> "Yamigarasu"
                41 -> "Akiko's Wok"
                42 -> "Toy Hammer"
                43 -> "Elysion"
                44 -> "Red Saber"
                45 -> "Meteor Cudgel"
                46 -> "Monkey King Bar"
                47 -> "Double Cannon"
                48 -> "Huge Battle Fan"
                49 -> "Tsumikiri J-Sword"
                50 -> "Sealed J-Sword"
                51 -> "Red Sword"
                52 -> "Crazy Tune"
                53 -> "Twin Chakram"
                54 -> "Wok of Akiko's Shop"
                55 -> "Lavis Blade"
                56 -> "Red Dagger"
                57 -> "Madam's Parasol"
                58 -> "Madam's Umbrella"
                59 -> "Imperial Pick"
                60 -> "Berdysh"
                61 -> "Red Partisan"
                62 -> "Flight Cutter"
                63 -> "Flight Fan"
                64 -> "Red Slicer"
                65 -> "Handgun: Guld"
                66 -> "Handgun: Milla"
                67 -> "Red Handgun"
                68 -> "Frozen Shooter"
                69 -> "Anti Android Rifle"
                70 -> "Rocket Punch"
                71 -> "Samba Maracas"
                72 -> "Twin Psychogun"
                73 -> "Drill Launcher"
                74 -> "Guld Milla"
                75 -> "Red Mechgun"
                76 -> "Belra Cannon"
                77 -> "Panzer Faust"
                78 -> "Summit Moon"
                79 -> "Windmill"
                80 -> "Evil Curst"
                81 -> "Flower Cane"
                82 -> "Hildebear's Cane"
                83 -> "Hildeblue's Cane"
                84 -> "Rabbit Wand"
                85 -> "Plantain Leaf"
                86 -> "Demonic Fork"
                87 -> "Striker of Chao"
                88 -> "Broom"
                89 -> "Prophets of Motav"
                90 -> "The Sigh of a God"
                91 -> "Twinkle Star"
                92 -> "Plantain Fan"
                93 -> "Twin Blaze"
                94 -> "Marina's Bag"
                95 -> "Dragon's Claw"
                96 -> "Panther's Claw"
                97 -> "S-Red's Blade"
                98 -> "Plantain Huge Fan"
                99 -> "Chameleon Scythe"
                100 -> "Yasminkov 3000R"
                101 -> "Ano Rifle"
                102 -> "Baranz Launcher"
                103 -> "Branch of Pakupaku"
                104 -> "Heart of Poumn"
                105 -> "Yasminkov 2000H"
                106 -> "Yasminkov 7000V"
                107 -> "Yasminkov 9000M"
                108 -> "Maser Beam"
                109 -> "Game Magazine"
                110 -> "Flower Bouquet"
                in 111..125 -> "Unused weapon model"
                126 -> "Bravace"
                127 -> "Custom Ray ver.OO"
                128 -> "Varista"
                129 -> "Justy-23ST"
                130 -> "Visk-235W"
                131 -> "Wals-MK2"
                132 -> "L&K14 Combat"
                133 -> "H&S25 Justice"
                134 -> "M&A60 Vise"
                135 -> "Meteor Smash"
                136 -> "Crush Bullet"
                137 -> "Final Impact"
                138 -> "Photon Launcher"
                139 -> "Guilty Light"
                140 -> "Red Scorpio"
                141 -> "NUG2000-Bazooka"
                142 -> "Club of Laconium"
                143 -> "Mace of Adaman"
                144 -> "Club of Zumiuran"
                145 -> "Brave Hammer"
                146 -> "Battle Verge"
                147 -> "Alive Aqhu"
                148 -> "Fire Scepter: Agni"
                149 -> "Ice Staff: Dagon"
                150 -> "Storm Wand: Indra"
                151 -> "Talis"
                152 -> "Durandal"
                153 -> "DB's Saber"
                154 -> "Kaladbolg"
                155 -> "Kamui"
                156 -> "Sange"
                157 -> "Yasha"
                158 -> "Flowen's Sword"
                159 -> "Dragon Slayer"
                160 -> "Last Survivor"
                161 -> "Bloody Art"
                162 -> "Blade Dance"
                163 -> "Cross Scar"
                164 -> "Vjaya"
                165 -> "Brionac"
                166 -> "Gae Bolg"
                167 -> "Slicer of Assassin"
                168 -> "Diska of Braveman"
                169 -> "Diska of Liberator"
                170 -> "Musashi"
                171 -> "Yamato"
                172 -> "Asuka"
                173 -> "S-Berill's Hands #0"
                174 -> "Gi Gue Bazooka"
                175 -> "Mahu"
                176 -> "Guardianna"
                177 -> "Hitogata"
                178 -> "Viridia Card"
                179 -> "Dark Flow"
                180 -> "Zanba"
                181 -> "Partisan of Lightning"
                182 -> "Demolition Comet"
                183 -> "Ruby Bullet"
                184 -> "Booma's Claw"
                185 -> "Gobooma's Claw"
                186 -> "Gigobooma's Claw"
                187 -> "G-Assassin's Sabers"
                188 -> "Morning Glory"
                189 -> "Dark Bridge"
                190 -> "Angel Harp"
                191 -> "Rainbow Baton"
                192 -> "Rika's Claw"
                193 -> "Nei's Claw"
                194 -> "Gal Wind"
                195 -> "Amore Rose"
                196 -> "Rappy's Fan"
                197 -> "Dark Meteor"
                198 -> "Sange & Yasha"
                199 -> "Slicer of Fanatic"
                200 -> "Lame d'Argent"
                201 -> "Excalibur"
                202 -> "Rage de Feu"
                203 -> "Daisy Chain"
                204 -> "Ophelie Seize"
                205 -> "Mille Marteaux"
                206 -> "Le Cogneur"
                207 -> "Commander Blade"
                208 -> "Vivienne"
                209 -> "Kusanagi"
                210 -> "Sacred Duster"
                211 -> "Guren"
                212 -> "Shouren"
                213 -> "Jizai"
                214 -> "Flamberge"
                215 -> "Yunchang"
                216 -> "Snake Spire"
                217 -> "Flapjack Flapper"
                218 -> "Getsugasan"
                219 -> "Maguwa"
                220 -> "Heaven Striker"
                221 -> "Cannon Rouge"
                222 -> "Meteor Rouge"
                223 -> "Solferino"
                224 -> "Clio"
                225 -> "Siren Glass Hammer"
                226 -> "Glide Divine"
                227 -> "Shichishito"
                228 -> "Murasame"
                229 -> "Daylight Scar"
                230 -> "Decalog"
                231 -> "5th Anniv. Blade"
                232 -> "Tyrell's Parasol"
                233 -> "Akiko's Cleaver"
                234 -> "Tanegashima"
                235 -> "Tree Clippers"
                236 -> "Nice Shot"
                237 -> "Unused Weapon38"
                238 -> "Unused Weapon39"
                239 -> "Ano Bazooka"
                240 -> "Synthesizer"
                241 -> "Bamboo Spear"
                242 -> "Kan'ei Tsuho"
                243 -> "Jitte"
                244 -> "Butterfly Net"
                245 -> "Syringe"
                246 -> "Battledore"
                247 -> "Racket"
                248 -> "Hammer"
                249 -> "Great Bouquet"
                250 -> "Mercurius Rod"
                251 -> "Rambling May"
                252 -> "Galatine"
                253 -> "Zero Divide"
                254 -> "Master Raven"
                255 -> "Last Swan"
                256 -> "Dual Bird"
                257 -> "Asteron Belt"
                258 -> "Phoenix Claw"
                259 -> "Girasole"
                260 -> "Rianov 303SNR family"
                261 -> "L&K38 Combat"
                262 -> "Phonon Maser"
                263 -> "Laconium Axe"
                264 -> "Earth Wand: Brownie"
                265 -> "Izmaela"
                266 -> "Kunai"
                267 -> "Tension Blaster"
                268 -> "Lollipop"
                269 -> "Valkyrie"
                270 -> "TypeSS/Sw"
                in 126..294 -> "Rare weapon skin $skin"
                else -> "Weapon skin $skin"
            }

        private fun barrierModelLabel(skin: Int): String =
            when (skin) {
                0 -> "Red Ring"
                1 -> "Tripolic Shield"
                2 -> "Standstill Shield"
                3 -> "Safety Heart"
                4 -> "Kasami Bracer"
                5 -> "Gods Shield Suzaku"
                6 -> "Gods Shield Genbu"
                7 -> "Gods Shield Byakko"
                8 -> "Gods Shield Seiryu"
                9 -> "Hunter's Shell"
                10 -> "Rico's Glasses"
                11 -> "Rico's Earring"
                12 -> "Blue Ring"
                13 -> "Yellow Ring"
                14 -> "Secure Feet"
                15 -> "Purple Ring"
                16 -> "Green Ring"
                17 -> "Black Ring"
                18 -> "White Ring"
                19 -> "Weapons Gold Shield"
                20 -> "Black Gear"
                21 -> "Works Guard"
                22 -> "Ragol Ring"
                23 -> "Blue Ring"
                24 -> "Blue Ring"
                25 -> "Blue Ring"
                26 -> "Blue Ring"
                27 -> "Blue Ring"
                28 -> "Blue Ring"
                29 -> "Blue Ring"
                30 -> "Green Ring"
                31 -> "Green Ring"
                32 -> "Green Ring"
                33 -> "Green Ring"
                34 -> "Green Ring"
                35 -> "Green Ring"
                36 -> "Green Ring"
                37 -> "Yellow Ring"
                38 -> "Yellow Ring"
                39 -> "Yellow Ring"
                40 -> "Yellow Ring"
                41 -> "Yellow Ring"
                42 -> "Yellow Ring"
                43 -> "Yellow Ring"
                44 -> "Purple Ring"
                45 -> "Purple Ring"
                46 -> "Purple Ring"
                47 -> "DF Shield"
                48 -> "From the Depths"
                49 -> "De Rol Le Shield"
                50 -> "Honeycomb Reflector"
                51 -> "Epsiguard"
                52 -> "Angel Ring"
                53 -> "Union Guard"
                54 -> "Gods Shield Kouryu"
                55 -> "Union Guard"
                56 -> "Union Guard"
                57 -> "Union Guard"
                in 58..66 -> "Genpei"
                else -> "Barrier skin $skin"
            }

        private fun toolModelLabel(skin: Int): String =
            when (skin) {
                0 -> "Mate / Fluid / Atomizer"
                1 -> "Event consumables"
                2 -> "Present"
                3 -> "Christmas Present"
                4 -> "Easter Egg"
                5 -> "Jack-O-Lantern"
                else -> "Tool skin $skin"
            }
    }
}
