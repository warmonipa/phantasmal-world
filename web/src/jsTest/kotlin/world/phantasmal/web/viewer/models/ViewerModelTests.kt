package world.phantasmal.web.viewer.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ViewerModelTests {
    @Test
    fun item_model_ranges_use_their_actual_kinds() {
        assertEquals(ViewerModel.ItemKind.Weapon, item(270).kind)
        assertEquals(ViewerModel.ItemKind.Mag, item(271).kind)
        assertEquals(ViewerModel.ItemKind.Mag, item(294).kind)
        assertEquals(ViewerModel.ItemKind.Barrier, item(295).kind)

        assertEquals(413, group(ViewerModel.ItemKind.Weapon).items.size)
        assertEquals(24, group(ViewerModel.ItemKind.Mag).items.size)
    }

    @Test
    fun weapon_catalog_keeps_named_items_that_share_models_separate() {
        val weapons = group(ViewerModel.ItemKind.Weapon).items
            .filterIsInstance<ViewerModel.Item>()
        val typeWeapons = weapons
            .filter { it.uiName.startsWith("Type") }

        assertEquals(weapons.size, weapons.map { it.slug }.toSet().size)
        assertEquals(true, weapons.all { it.index in 0..270 })
        assertEquals(true, weapons.all { it.textureIndex in 0..294 })
        assertEquals(30, typeWeapons.size)
        assertWeapon(0x00DE00, "TypeSL/Saber", 0, 0)
        assertWeapon(0x00DE01, "TypeSL/Slicer", 4, 4)
        assertWeapon(0x00E800, "TypeGU/Hand", 5, 5)
        assertWeapon(0x00EB00, "TypeSH/Shot", 8, 8)

        assertWeapon(0x000D02, "Nei's Claw [000D02]", 12, 273)
        assertWeapon(0x009B00, "Nei's Claw [009B00]", 193, 193)
    }

    @Test
    fun weapon_catalog_contains_every_section_id_card_texture() {
        val cards = listOf(
            "Viridia Card",
            "Greenill Card",
            "Skyly Card",
            "Bluefull Card",
            "Purplenum Card",
            "Pinkal Card",
            "Redria Card",
            "Oran Card",
            "Yellowboze Card",
            "Whitill Card",
        )

        cards.forEachIndexed { skinOffset, name ->
            val textureIndex = if (skinOffset == 0) 178 else 277 + skinOffset
            val item = group(ViewerModel.ItemKind.Weapon).items
                .filterIsInstance<ViewerModel.Item>()
                .single { it.uiName == name }

            assertEquals(178, item.index)
            assertEquals(textureIndex, item.textureIndex)
        }
    }

    @Test
    fun weapon_texture_variants_reuse_the_base_geometry() {
        assertVariant(46, 288, "Black King Bar")
        assertVariant(68, 275, "Snow Queen")
        assertVariant(77, 291, "Iron Faust")
        assertVariant(85, 287, "Fatsia")
        assertVariant(108, 289, "Power Maser")
        assertVariant(109, 293, "LOGiN")
    }

    @Test
    fun item_model_labels_follow_itempmt_model_bindings() {
        assertEquals(true, item(111).uiName.contains("Unused weapon model"))
        assertEquals(true, item(125).uiName.contains("Unused weapon model"))
        assertEquals(true, item(126).uiName.endsWith("Bravace"))
        assertEquals(true, item(127).uiName.endsWith("Custom Ray ver.OO"))
        assertEquals(true, item(128).uiName.endsWith("Varista"))
        assertEquals(true, item(129).uiName.endsWith("Justy-23ST"))
        assertEquals(true, item(130).uiName.endsWith("Visk-235W"))
        val expectedNames = listOf(
            "Wals-MK2",
            "L&K14 Combat",
            "H&S25 Justice",
            "M&A60 Vise",
            "Meteor Smash",
            "Crush Bullet",
            "Final Impact",
            "Photon Launcher",
            "Guilty Light",
            "Red Scorpio",
            "NUG2000-Bazooka",
            "Club of Laconium",
            "Mace of Adaman",
            "Club of Zumiuran",
            "Brave Hammer",
            "Battle Verge",
            "Alive Aqhu",
            "Fire Scepter: Agni",
            "Ice Staff: Dagon",
            "Storm Wand: Indra",
            "Talis",
            "Durandal",
            "DB's Saber",
            "Kaladbolg",
            "Kamui",
            "Sange",
            "Yasha",
            "Flowen's Sword",
            "Dragon Slayer",
            "Last Survivor",
            "Bloody Art",
            "Blade Dance",
            "Cross Scar",
            "Vjaya",
            "Brionac",
            "Gae Bolg",
            "Slicer of Assassin",
            "Diska of Braveman",
            "Diska of Liberator",
            "Musashi",
            "Yamato",
            "Asuka",
            "S-Berill's Hands #0",
            "Gi Gue Bazooka",
            "Mahu",
            "Guardianna",
            "Hitogata",
            "Viridia Card",
            "Dark Flow",
            "Zanba",
            "Partisan of Lightning",
            "Demolition Comet",
            "Ruby Bullet",
            "Booma's Claw",
            "Gobooma's Claw",
            "Gigobooma's Claw",
            "G-Assassin's Sabers",
            "Morning Glory",
            "Dark Bridge",
            "Angel Harp",
            "Rainbow Baton",
            "Rika's Claw",
            "Nei's Claw",
            "Gal Wind",
            "Amore Rose",
            "Rappy's Fan",
            "Dark Meteor",
            "Sange & Yasha",
            "Slicer of Fanatic",
            "Lame d'Argent",
            "Excalibur",
            "Rage de Feu",
            "Daisy Chain",
            "Ophelie Seize",
            "Mille Marteaux",
            "Le Cogneur",
            "Commander Blade",
            "Vivienne",
            "Kusanagi",
            "Sacred Duster",
            "Guren",
            "Shouren",
            "Jizai",
            "Flamberge",
            "Yunchang",
            "Snake Spire",
            "Flapjack Flapper",
            "Getsugasan",
            "Maguwa",
            "Heaven Striker",
            "Cannon Rouge",
            "Meteor Rouge",
            "Solferino",
            "Clio",
            "Siren Glass Hammer",
            "Glide Divine",
            "Shichishito",
            "Murasame",
            "Daylight Scar",
            "Decalog",
            "5th Anniv. Blade",
            "Tyrell's Parasol",
            "Akiko's Cleaver",
            "Tanegashima",
            "Tree Clippers",
            "Nice Shot",
            "Unused Weapon38",
            "Unused Weapon39",
            "Ano Bazooka",
            "Synthesizer",
            "Bamboo Spear",
            "Kan'ei Tsuho",
            "Jitte",
            "Butterfly Net",
            "Syringe",
            "Battledore",
            "Racket",
            "Hammer",
            "Great Bouquet",
            "Mercurius Rod",
            "Rambling May",
            "Galatine",
            "Zero Divide",
            "Master Raven",
            "Last Swan",
            "Dual Bird",
            "Asteron Belt",
            "Phoenix Claw",
            "Girasole",
            "Rianov 303SNR family",
            "L&K38 Combat",
            "Phonon Maser",
            "Laconium Axe",
            "Earth Wand: Brownie",
            "Izmaela",
            "Kunai",
            "Tension Blaster",
            "Lollipop",
            "Valkyrie",
            "TypeSS/Sw",
        )
        expectedNames.forEachIndexed { offset, name ->
            assertEquals(true, item(131 + offset).uiName.endsWith(name))
        }
    }

    private fun item(index: Int): ViewerModel.Item =
        ViewerModel.findBySlug("ItemModel_$index") as ViewerModel.Item

    private fun assertVariant(modelIndex: Int, textureIndex: Int, name: String) {
        val item = assertNotNull(
            ViewerModel.findBySlug("ItemModel_${modelIndex}_Texture_$textureIndex")
        ) as ViewerModel.Item
        assertEquals(modelIndex, item.index)
        assertEquals(textureIndex, item.textureIndex)
        assertEquals(true, item.uiName.endsWith(name))
    }

    private fun assertWeapon(
        itemTypeId: Int,
        name: String,
        modelIndex: Int,
        textureIndex: Int,
    ) {
        val slug = "Weapon_${itemTypeId.toString(16).padStart(6, '0').uppercase()}"
        val item = assertNotNull(ViewerModel.findBySlug(slug)) as ViewerModel.Item
        assertEquals(name, item.uiName)
        assertEquals(modelIndex, item.index)
        assertEquals(textureIndex, item.textureIndex)
    }

    private fun group(kind: ViewerModel.ItemKind): ViewerModel.Group =
        ViewerModel.ITEM_GROUPS.single { group ->
            (group.items.first() as ViewerModel.Item).kind == kind
        }
}
