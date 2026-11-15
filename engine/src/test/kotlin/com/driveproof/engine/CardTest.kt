package com.driveproof.engine

import com.driveproof.model.BasicKind
import com.driveproof.model.Exempt
import com.driveproof.model.Principle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 한 장 고르기와 문구(E-6). 문장은 `strings.xml`을 XML로 읽어 만든다.
 * 그 문장을 demo-sim.js 출력과 글자까지 비교한다(implementation.md "문구").
 */
class CardTest {

    private val strings: Map<String, String> get() = Golden.cardStrings

    private fun text(key: String): String = Golden.cardText(key)

    private fun render(card: Card) = renderCard(card, ::text)

    private val kinds = mapOf("좌회전" to SceneKind.LEFT_TURN, "우회전" to SceneKind.RIGHT_TURN, "유턴" to SceneKind.U_TURN)

    private fun num(o: JsonObject, k: String): Double? = o[k]?.let { if (it is JsonNull) null else it.jsonPrimitive.double }

    /** 골든의 장면을 `SceneRec`으로 옮긴다. 엔진이 아직 만들지 않는 과속방지턱, 스쿨존 장면도 여기서는 쓴다 */
    private fun goldenScenes(g: JsonObject): List<SceneRec> = g.getValue("scenes").jsonArray.map { it.jsonObject }.map { o ->
        val at = (o.getValue("t").jsonPrimitive.double * 1000).toLong()
        val s = o.getValue("s").jsonPrimitive.double
        val path = if (o.getValue("est").jsonPrimitive.boolean) ScenePath.VEHICLE else ScenePath.NAVI
        when (o.getValue("principle").jsonPrimitive.content) {
            "signal" -> SceneRec(Principle.SIGNAL, kinds.getValue(o.getValue("kind").jsonPrimitive.content), at, s, path, onDistanceM = num(o, "value"))
            "decel" -> {
                val bump = o.getValue("target").jsonPrimitive.content == "bump"
                val kind = if (bump) SceneKind.SPEED_BUMP else kinds.getValue(o.getValue("kind").jsonPrimitive.content)
                SceneRec(
                    Principle.DECEL, kind, at, s, path,
                    maxDecel = num(o, "aMax"), passKmh = num(o, "passV"), decelScore = num(o, "decelS"), passScore = num(o, "passS"),
                )
            }
            else -> SceneRec(Principle.ZONE, SceneKind.SCHOOL_ZONE, at, s, path, maxKmh = num(o, "max"), limitKmh = o.getValue("limit").jsonPrimitive.int)
        }
    }

    private fun goldenBasic(g: JsonObject): List<BasicRec> {
        val map = mapOf("decel" to BasicKind.DECEL, "accel" to BasicKind.ACCEL, "over" to BasicKind.OVER)
        return g.getValue("basic").jsonObject.flatMap { (key, list) ->
            list.jsonArray.map { it.jsonObject }.map {
                val exempt = it.getValue("exempt")
                BasicRec(
                    map.getValue(key), (it.getValue("t").jsonPrimitive.double * 1000).toLong(), it.getValue("a").jsonPrimitive.double,
                    it.getValue("v").jsonPrimitive.double, if (exempt is JsonNull) Exempt.NONE else Exempt.valueOf(exempt.jsonPrimitive.content.uppercase()),
                )
            }
        }
    }

    private fun assertMatchesGolden(id: String, card: Card) {
        val want = Golden.result(id).getValue("card").jsonObject
        val wantLines = want.getValue("lines").jsonArray.map { it.jsonObject }
        val got = render(card)
        assertEquals("$id 줄 문구", wantLines.map { it.getValue("text").jsonPrimitive.content }, got.lines)
        assertEquals(
            "$id 줄 종류",
            wantLines.map { if (it.getValue("partial").jsonPrimitive.boolean) "PARTIAL" else it.getValue("kind").jsonPrimitive.content.uppercase() },
            card.lines.map { it.kind.name },
        )
        assertEquals("$id 원칙", wantLines.map { it.getValue("principle").jsonPrimitive.content.uppercase() }, card.lines.map { it.topic.name })
        assertEquals("$id 추정", wantLines.map { it.getValue("est").jsonPrimitive.boolean }, card.lines.map { it.estimated })
        assertEquals("$id 주의 줄", want.getValue("notes").jsonArray.map { it.jsonPrimitive.content }, got.notes)
    }

    /** 골든의 장면으로 한 장을 고른다. 시나리오 1~5의 문구가 글자까지 같다 */
    @Test
    fun cardTextMatchesReferenceForAllScenarios() {
        for (id in Golden.ids) {
            val g = Golden.result(id)
            val card = buildCard(
                goldenScenes(g), goldenBasic(g),
                guided = g.getValue("route").jsonPrimitive.content == "navi",
                hadCaution = g.getValue("hadCau").jsonPrimitive.boolean,
            )
            assertMatchesGolden(id, card)
        }
    }

    /** 엔진이 판정한 장면으로 만든 한 장. 재생부터 문구까지 시나리오 1~5가 글자까지 같다 */
    @Test
    fun cardFromTheEngineMatchesReference() {
        for (id in Golden.ids) assertMatchesGolden(id, Golden.run(TripEngine(), Golden.signals(id)).card)
    }

    /**
     * 6개월 시드의 주행 102건으로 한 장을 고른다. demo-history.js가 만든 한 장과 문구, 고칠 점의 원칙이 같다.
     * 시드는 demo 빌드 에셋(`m6.json`)이고 기대값은 `golden/m6.json`의 `cards`다.
     */
    @Test
    fun seedTripCardsMatchReference() {
        val seed = Json.parseToJsonElement(File("../app/src/demo/assets/m6.json").readText()).jsonObject
        val cards = Json.parseToJsonElement(Golden::class.java.getResource("/m6.json")!!.readText()).jsonObject.getValue("cards").jsonArray
        val trips = seed.getValue("trips").jsonArray.map { it.jsonObject }
        assertEquals(102, trips.size)
        trips.forEachIndexed { i, t ->
            val scenes = t.getValue("scenes").jsonArray.map { it.jsonObject }.map { o ->
                SceneRec(
                    Principle.valueOf(o.getValue("principle").jsonPrimitive.content), SceneKind.valueOf(o.getValue("kind").jsonPrimitive.content), 0,
                    o.getValue("s").jsonPrimitive.double, ScenePath.NAVI,
                    onDistanceM = num(o, "onDistanceM"), maxDecel = num(o, "maxDecel"), passKmh = num(o, "passKmh"),
                    decelScore = num(o, "decelScore"), passScore = num(o, "passScore"), maxKmh = num(o, "maxKmh"), limitKmh = o["limitKmh"]?.jsonPrimitive?.int,
                )
            }
            val basic = t.getValue("basicEvents").jsonArray.map { it.jsonObject }.map {
                BasicRec(BasicKind.valueOf(it.getValue("kind").jsonPrimitive.content), 0, 0.0, 0.0, Exempt.valueOf(it.getValue("exempt").jsonPrimitive.content))
            }
            val card = buildCard(scenes, basic, guided = true, hadCaution = true)
            val want = cards[i].jsonObject
            assertEquals("주행 $i 문구", want.getValue("lines").jsonArray.map { it.jsonPrimitive.content }, render(card).lines)
            assertEquals("주행 $i 주의 줄", want.getValue("notes").jsonArray.map { it.jsonPrimitive.content }, render(card).notes)
            val fix = want.getValue("fix")
            assertEquals("주행 $i 고칠 점", if (fix is JsonNull) null else fix.jsonPrimitive.content, card.lines.firstOrNull { it.kind == LineKind.FIX }?.topic?.name)
        }
    }

    /** 이슈 완료 기준 */
    @Test
    fun scenario3TextsAreTheOnesInTheIssue() {
        val g = Golden.result("s3")
        val got = render(buildCard(goldenScenes(g), goldenBasic(g), guided = true, hadCaution = true))
        assertEquals(
            listOf("우회전 35m 전에 깜빡이를 켰어요", "스쿨존 최고 37km/h. 30km/h 이하로 달려 주세요"),
            got.lines,
        )
    }

    private fun signal(kind: SceneKind, onM: Double?, path: ScenePath = ScenePath.NAVI) =
        SceneRec(Principle.SIGNAL, kind, 0, if (onM == null) 0.0 else minOf(1.0, onM / 30), path, onDistanceM = onM)

    private fun decel(kind: SceneKind, s: Double, maxDecel: Double = 5.0, passKmh: Double = 20.0, decelScore: Double? = null, passScore: Double? = null) =
        SceneRec(Principle.DECEL, kind, 0, s, ScenePath.NAVI, maxDecel = maxDecel, passKmh = passKmh, decelScore = decelScore, passScore = passScore)

    private fun zone(maxKmh: Double, limit: Int = 30) =
        SceneRec(Principle.ZONE, SceneKind.SCHOOL_ZONE, 0, (1 - (maxKmh - limit) / 20).coerceIn(0.0, 1.0), ScenePath.NAVI, maxKmh = maxKmh, limitKmh = limit)

    private fun hardBrake(exempt: Exempt = Exempt.NONE) = BasicRec(BasicKind.DECEL, 0, 16.0, 40.0, exempt)

    private fun lines(scenes: List<SceneRec>, basic: List<BasicRec> = emptyList(), guided: Boolean = true, hadCaution: Boolean = false) =
        render(buildCard(scenes, basic, guided, hadCaution)).let { it.lines + it.notes }

    @Test
    fun partOfTheTurnsSignalledInTimeSaysHowManyOutOfHowMany() {
        // 회전 3번 중 2번 성공 (implementation.md 경계 시나리오)
        val got = lines(listOf(signal(SceneKind.LEFT_TURN, 40.0), signal(SceneKind.RIGHT_TURN, 35.0), signal(SceneKind.LEFT_TURN, 12.0)))
        assertEquals(listOf("회전 3번 중 2번은 30m 전부터 깜빡이를 켰어요"), got)
    }

    @Test
    fun allTurnsOfOneKindUseItsName() {
        assertEquals(listOf("좌회전 2번 모두 30m 전부터 깜빡이를 켰어요"), lines(listOf(signal(SceneKind.LEFT_TURN, 40.0), signal(SceneKind.LEFT_TURN, 31.0))))
    }

    @Test
    fun twoSchoolZonesOneOverTheLimit() {
        // 스쿨존 2곳 (implementation.md 경계 시나리오). 구역은 일부 성공이라 칭찬이 되고, 남은 원칙이 없어 한 줄이다
        assertEquals(listOf("스쿨존 2곳 중 1곳에서 30km/h 이하로 달렸어요"), lines(listOf(zone(28.0), zone(41.0)), hadCaution = true))
        // 다른 원칙이 칭찬을 가져가면 구역이 고칠 점이 된다
        assertEquals(
            listOf("좌회전 40m 전에 깜빡이를 켰어요", "스쿨존 2곳 과속(최고 44km/h). 30km/h 이하로 달려 주세요"),
            lines(listOf(signal(SceneKind.LEFT_TURN, 40.0), zone(44.0), zone(41.0)), hadCaution = true),
        )
    }

    @Test
    fun noSuccessAndLowAverageLeavesOnlyTheFix() {
        // 성공 장면이 없고 부분 점수 평균이 0.5 미만이다. 해낸 것 줄을 비운다
        val got = lines(listOf(signal(SceneKind.LEFT_TURN, 9.0), decel(SceneKind.LEFT_TURN, 0.2, maxDecel = 13.0)))
        assertEquals(listOf("좌회전 전 최대 감속 초당 13km/h. 회전 50m 전부터 속도를 줄여 주세요"), got)
    }

    @Test
    fun missedSignalComesBeforeTheAverage() {
        val got = lines(listOf(signal(SceneKind.LEFT_TURN, 40.0), signal(SceneKind.RIGHT_TURN, null), decel(SceneKind.LEFT_TURN, 1.0), decel(SceneKind.RIGHT_TURN, 1.0)))
        assertEquals(listOf("회전 2번 모두 미리 속도를 줄였어요", "우회전 1번은 깜빡이를 안 켰어요. 회전 30m 전부터 켜 주세요"), got)
    }

    @Test
    fun lateTurnIsNamedWhenTheAverageIsOverThirty() {
        val got = lines(listOf(signal(SceneKind.LEFT_TURN, 60.0), signal(SceneKind.RIGHT_TURN, 22.0), decel(SceneKind.LEFT_TURN, 1.0), decel(SceneKind.RIGHT_TURN, 1.0)))
        assertEquals("우회전 1번은 22m 전에 깜빡이를 켰어요. 30m 전부터 켜 주세요", got[1])
    }

    @Test
    fun averageIsUsedWhenItIsUnderThirty() {
        val got = lines(listOf(signal(SceneKind.LEFT_TURN, 10.0), signal(SceneKind.RIGHT_TURN, 20.0), decel(SceneKind.LEFT_TURN, 1.0), decel(SceneKind.RIGHT_TURN, 1.0)))
        assertEquals("깜빡이를 평균 15m 전에 켰어요. 회전 30m 전부터 켜 주세요", got[1])
    }

    @Test
    fun speedBumpTakenTooFastSaysThePassSpeed() {
        val got = lines(
            listOf(signal(SceneKind.LEFT_TURN, 40.0), decel(SceneKind.SPEED_BUMP, 0.5, passKmh = 40.0, decelScore = 1.0, passScore = 0.5)),
            hadCaution = true,
        )
        assertEquals("과속방지턱을 40km/h로 넘었어요. 30km/h 이하로 통과해 주세요", got[1])
    }

    @Test
    fun speedBumpsAreCountedInPlaces() {
        val bumps = listOf(decel(SceneKind.SPEED_BUMP, 1.0, decelScore = 1.0, passScore = 1.0), decel(SceneKind.SPEED_BUMP, 1.0, decelScore = 1.0, passScore = 1.0))
        assertEquals(listOf("과속방지턱 2곳 모두 미리 속도를 줄였어요"), lines(bumps, hadCaution = true))
    }

    @Test
    fun basicItemsAreUsedOnlyWhenThereAreNoScenes() {
        // 기본 항목만 있는 주행 (implementation.md 경계 시나리오)
        val accel = BasicRec(BasicKind.ACCEL, 0, 12.0, 20.0)
        assertEquals(listOf("급감속 2번. 일찍 속도를 줄여 보세요", "급가속·급감속·과속만 평가했어요"), lines(emptyList(), listOf(hardBrake(), hardBrake(), accel)))
        assertEquals(listOf("급가속 1번. 가속 페달을 천천히 밟아 보세요", "급가속·급감속·과속만 평가했어요"), lines(emptyList(), listOf(accel, hardBrake(Exempt.TELLTALE))))
        // 장면이 있으면 기본 항목은 한 장에 쓰지 않는다
        assertEquals(listOf("좌회전 40m 전에 깜빡이를 켰어요"), lines(listOf(signal(SceneKind.LEFT_TURN, 40.0)), listOf(hardBrake())))
    }

    @Test
    fun nothingToShow() {
        assertEquals(listOf("기록할 장면이 없었어요"), lines(emptyList()))
        assertEquals(listOf("기록할 장면이 없었어요"), lines(emptyList(), listOf(hardBrake(Exempt.SCENE))))
    }

    @Test
    fun drivingWithoutGuidanceAddsANote() {
        val estimated = listOf(signal(SceneKind.RIGHT_TURN, 40.0, ScenePath.VEHICLE))
        val card = buildCard(estimated, emptyList(), guided = false, hadCaution = false)
        assertTrue(card.lines.single().estimated)
        assertEquals(listOf("내비 안내가 없어 회전 장면만 평가했어요"), render(card).notes)
        // 주의 지점 이벤트가 왔으면 붙이지 않는다
        assertEquals(emptyList<String>(), render(buildCard(estimated, emptyList(), guided = false, hadCaution = true)).notes)
    }

    @Test
    fun cardJsonCarriesKeysArgsAndTheSentences() {
        val card = Golden.run(TripEngine(), Golden.signals("s1")).card.withNote(CardKeys.NOTE_GAP)
        val r = renderCard(card, ::text, scoreKey = "card_score_first", scoreArgs = mapOf("n" to 1), total = 78.706754, delta = null)
        assertEquals("좌회전할 때 깜빡이를 켰어요", r.firstLine)
        assertEquals("기록 1회째", r.scoreLine)

        val json = Json.parseToJsonElement(r.json).jsonObject
        val lines = json.getValue("lines").jsonArray.map { it.jsonObject }
        assertEquals(listOf("PARTIAL", "FIX"), lines.map { it.getValue("kind").jsonPrimitive.content })
        assertEquals(listOf("SIGNAL", "DECEL"), lines.map { it.getValue("principle").jsonPrimitive.content })
        assertEquals(listOf("card_partial_signal", "card_fix_decel_turn"), lines.map { it.getValue("key").jsonPrimitive.content })
        assertEquals(12, lines[1].getValue("args").jsonObject.getValue("a").jsonPrimitive.int)
        assertEquals("card_word_left_turn", lines[1].getValue("args").jsonObject.getValue("turn").jsonPrimitive.content)
        assertEquals("좌회전 전 최대 감속 초당 12km/h. 회전 50m 전부터 속도를 줄여 주세요", lines[1].getValue("text").jsonPrimitive.content)
        assertEquals(false, lines[0].getValue("estimated").jsonPrimitive.boolean)

        val note = json.getValue("notes").jsonArray.single().jsonObject
        assertEquals("card_note_gap", note.getValue("key").jsonPrimitive.content)
        assertEquals("기록이 끊긴 구간은 점수에서 뺐어요", note.getValue("text").jsonPrimitive.content)

        val score = json.getValue("score").jsonObject
        assertEquals(78.7, score.getValue("T").jsonPrimitive.double, 0.0)
        assertTrue(score.getValue("delta") is JsonNull)
        assertEquals("2026-11-10", json.getValue("ruleVersion").jsonPrimitive.content)
    }

    @Test
    fun emptyCardFallsBackToTheNoteForTheNotification() {
        val r = render(buildCard(emptyList(), emptyList(), guided = true, hadCaution = false))
        assertEquals("기록할 장면이 없었어요", r.firstLine)
        assertNull(r.scoreLine)
    }

    @Test
    fun everyKeyTheEngineCanProduceIsInStringsXml() {
        val used = listOf(
            "praise_signal_one", "praise_signal_all", "praise_signal_some", "praise_decel_one", "praise_decel_all", "praise_decel_all_both",
            "praise_decel_some", "praise_zone_one", "praise_zone_all", "praise_zone_some", "partial_signal", "partial_decel",
            "fix_signal_miss", "fix_signal_late", "fix_signal_one", "fix_signal_avg", "fix_decel_turn", "fix_decel_bump", "fix_decel_bump_speed",
            "fix_zone_one", "fix_zone_many", "fix_basic_decel", "fix_basic_accel", "fix_basic_over",
            "change_signal", "change_decel", "change_zone", "word_signal_not_on",
            "note_route", "note_basic", "note_none", "note_gap", "score_first", "score_up", "score_down", "score_same",
            "word_left_turn", "word_right_turn", "word_u_turn", "word_turn", "word_bump", "word_turn_and_bump", "word_times", "word_places",
        ).map { "card_$it" }
        assertEquals(emptyList<String>(), used.filter { it !in strings })
        assertEquals("쓰지 않는 키", emptyList<String>(), strings.keys.filter { it !in used }.sorted())
    }
}
