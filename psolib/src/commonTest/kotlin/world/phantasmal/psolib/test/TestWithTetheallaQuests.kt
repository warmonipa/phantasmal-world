package world.phantasmal.psolib.test

/**
 * Applies [process] to all quest files provided with Tethealla version 0.143.
 * [process] is called with the path to the file and the file name.
 *
 * Files live alongside other corpora (e.g. qedit BB) under the shared
 * [QUEST_RESOURCE_PREFIX] tree; the path layout below the prefix is the
 * Tethealla snapshot's original (battle/, ep1/, princ/, solo/, ...).
 * Challenge-mode entries were deduplicated against the qedit corpus
 * (md5-equal) and removed from this list — see [QEDIT_BB_QUESTS] for
 * `chl/ep1/1cN_e.qst` and `chl/ep2/2cN_e.qst`.
 */
inline fun testWithTetheallaQuests(process: (path: String, filename: String) -> Unit) {
    for (file in TETHEALLA_QUESTS) {
        val lastSlashIdx = file.lastIndexOf('/')
        process(QUEST_RESOURCE_PREFIX + file, file.drop(lastSlashIdx + 1))
    }
}

/**
 * Shared classpath prefix for all in-repo quest fixtures (Tethealla snapshot,
 * qedit Wiki BB corpus, etc.). See `psolib/src/commonTest/resources/quests/`.
 */
const val QUEST_RESOURCE_PREFIX = "/quests"

val TETHEALLA_QUESTS = listOf(
    "/battle/1.qst",
    "/battle/2.qst",
    "/battle/3.qst",
    "/battle/4.qst",
    "/battle/5.qst",
    "/battle/6.qst",
    "/battle/7.qst",
    "/battle/8.qst",
    "/ep1/event/ma1.qst",
    "/ep1/event/ma4-a.qst",
    "/ep1/event/ma4-b.qst",
    "/ep1/event/ma4-c.qst",
    "/ep1/event/princgift.qst",
    "/ep1/event/sunset base.qst",
    "/ep1/event/white day (jp).qst",
    "/ep1/ext/en1.qst",
    "/ep1/ext/en2.qst",
    "/ep1/ext/en3.qst",
    "/ep1/ext/en4.qst",
    "/ep1/ext/mop-up1.qst",
    "/ep1/ext/mop-up2.qst",
    "/ep1/ext/mop-up3.qst",
    "/ep1/ext/mop-up4.qst",
    "/ep1/ext/todays rate.qst",
    "/ep1/recovery/gallon.qst",
    "/ep1/recovery/lost havoc vulcan.qst",
    "/ep1/recovery/lost heat sword.qst",
    "/ep1/recovery/lost ice spinner.qst",
    "/ep1/recovery/lost soul blade.qst",
    "/ep1/recovery/rappy holiday.qst",
    "/ep2/event/beach laughter.qst",
    "/ep2/event/dream messenger (jp).qst",
    "/ep2/event/ma2.qst",
    // ma4-a.qst seems corrupt, doesn't work in qedit either.
    "/ep2/event/ma4-b.qst",
    "/ep2/event/ma4-c.qst",
    "/ep2/event/singing by the beach.qst",
    "/ep2/shop/gallon.qst",
    "/ep2/vr/reach for the dream.qst",
    "/ep2/vr/respective tomorrow (jp).qst",
    "/ep4/event/clarie's deal.qst",
    "/ep4/event/login.qst",
    "/ep4/event/ma4-a.qst",
    "/ep4/event/ma4-b.qst",
    "/ep4/event/ma4-c.qst",
    "/ep4/event/wildhouse.qst",
    "/ep4/ext/new mop-up operation 1 (jp).qst",
    "/ep4/ext/new mop-up operation 2 (jp).qst",
    "/ep4/ext/new mop-up operation 3 (jp).qst",
    "/ep4/ext/new mop-up operation 4 (jp).qst",
    "/ep4/ext/new mop-up operation 5 (jp).qst",
    "/ep4/shop/itempresent.qst",
    "/ep4/shop/quest205.qst",
    "/ep4/vr/max3.qst",
    "/solo/ep1/01.qst",
    "/solo/ep1/02.qst",
    "/solo/ep1/03.qst",
    "/solo/ep1/04.qst",
    "/solo/ep1/05.qst",
    "/solo/ep1/06.qst",
    "/solo/ep1/07.qst",
    "/solo/ep1/08.qst",
    "/solo/ep1/09.qst",
    "/solo/ep1/10.qst",
    "/solo/ep1/11.qst",
    "/solo/ep1/12.qst",
    "/solo/ep1/13.qst",
    "/solo/ep1/14.qst",
    "/solo/ep1/15.qst",
    "/solo/ep1/16.qst",
    "/solo/ep1/17.qst",
    "/solo/ep1/18.qst",
    "/solo/ep1/19.qst",
    "/solo/ep1/20.qst",
    "/solo/ep1/21.qst",
    "/solo/ep1/22.qst",
    "/solo/ep1/23.qst",
    "/solo/ep1/24.qst",
    "/solo/ep1/25.qst",
    "/solo/ep1/side/26.qst",
    "/solo/ep1/side/goodluck.qst",
    "/solo/ep1/side/quest035.qst",
    "/solo/ep1/side/quest073.qst",
    "/solo/ep2/01.qst",
    "/solo/ep4/01-blackpaper.qst",
    "/solo/ep4/02-pioneer spirit.qst",
    "/solo/ep4/03-warrior pride.qst",
    "/solo/ep4/04-restless lion.qst",
    "/solo/ep4/blackpaper2.qst",
    "/solo/ep4/wilderending.qst",
    "/ep1/gov/4-3 hero & daughter.qst",
    "/ep2/lab/8-2 desire's end.qst",
    "/ep4/gov/9-3 reality & truth.qst",
)
