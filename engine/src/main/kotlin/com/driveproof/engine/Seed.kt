package com.driveproof.engine

import com.driveproof.model.BasicKind
import com.driveproof.model.Consts
import com.driveproof.model.Exempt
import com.driveproof.model.Principle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 시드 파일(`m6.json`). 규격은 implementation.md "시나리오 파일 규격"이다. 재생하지 않고 바로 저장한다.
 * 파일의 `weekly`(데모가 계산한 주간 요약)는 읽지 않는다. 저장한 기록에서 `:scoring`의 `weeklySummaries`로 다시 계산한다.
 */
data class Seed(val name: String, val trips: List<SeedTrip>)

/** 시드의 주행 한 건. [daysAgo]는 마지막 주 끝(일요일)에서 며칠 전인지다. 장면과 이벤트에는 시각이 없다(0) */
data class SeedTrip(
    val daysAgo: Int,
    val km: Double,
    val scenes: List<SceneRec>,
    val basic: List<BasicRec>,
    val exempts: List<SeedExempt>,
)

/**
 * 점수에서 뺀 급감속 한 건(`ExemptEvent`). [decel]은 감속(km/h/s)이다.
 * [reason]은 규칙 이름에 쓸 값이다. 경고등 이름(`ABS`, `ESC`, `FCA`)이거나 회전 구간이면 `TURN`이다.
 */
data class SeedExempt(val decel: Double, val reason: String, val mode: Exempt)

/**
 * 저장할 주행 한 건. 시드의 날짜를 기준일에 맞춰 달력 시각으로 옮긴 것이다.
 * [endAt]은 주행이 끝난 달력 시각(ms)이다. 장면과 이벤트의 시각도 [endAt]이다. [card]는 이 주행의 한 장이다.
 */
data class SeedRecord(
    val endAt: Long,
    val km: Double,
    val scenes: List<SceneRec>,
    val basic: List<BasicRec>,
    val exempts: List<SeedExempt>,
    val card: Card,
)

/** 시드 JSON을 읽는다. 형식이 틀리면 [IllegalArgumentException]을 던진다 */
fun parseSeed(json: String): Seed {
    try {
        val root = Json.parseToJsonElement(json).jsonObject
        require(root.text("kind") == "seed") { "kind가 seed가 아니다: ${root.text("kind")}" }
        val trips = (root["trips"] ?: throw IllegalArgumentException("trips가 없다")).jsonArray.map { e ->
            val t = e.jsonObject
            SeedTrip(
                daysAgo = t.getValue("daysAgo").jsonPrimitive.int.also { require(it >= 0) { "daysAgo는 0 이상이다: $it" } },
                km = t.getValue("km").jsonPrimitive.double,
                scenes = t.list("scenes").map(::sceneOf),
                basic = t.list("basicEvents").map {
                    BasicRec(BasicKind.valueOf(it.text("kind")!!), 0, 0.0, 0.0, Exempt.valueOf(it.text("exempt") ?: "NONE"))
                },
                exempts = t.list("exemptEvents").map {
                    SeedExempt(it.getValue("decel").jsonPrimitive.double, it.text("reason") ?: "", Exempt.valueOf(it.text("mode")!!))
                },
            )
        }
        require(trips.isNotEmpty()) { "trips가 비었다" }
        return Seed(root.text("name") ?: "", trips)
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: RuntimeException) {
        throw IllegalArgumentException("시드 형식이 틀렸다: ${e.message}", e)
    }
}

private fun JsonObject.text(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

private fun JsonObject.list(key: String): List<JsonObject> = this[key]?.jsonArray?.map { it.jsonObject } ?: emptyList()

private fun num(e: JsonElement?): Double? = if (e == null || e is JsonNull) null else e.jsonPrimitive.double

/** 장면의 필드 이름은 [SceneRec]과 같다. 시드의 장면은 모두 내비 경로다 */
private fun sceneOf(o: JsonObject) = SceneRec(
    principle = Principle.valueOf(o.text("principle")!!),
    kind = SceneKind.valueOf(o.text("kind")!!),
    at = 0,
    s = o.getValue("s").jsonPrimitive.double,
    path = ScenePath.NAVI,
    onDistanceM = num(o["onDistanceM"]),
    maxDecel = num(o["maxDecel"]),
    hardDecel = num(o["hardDecel"]),
    passKmh = num(o["passKmh"]),
    decelScore = num(o["decelScore"]),
    passScore = num(o["passScore"]),
    maxKmh = num(o["maxKmh"]),
    limitKmh = o["limitKmh"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int,
)

/**
 * 시드의 "마지막 주 끝". [today]보다 앞선 가장 가까운 일요일이다.
 * 오늘이 일요일이면 그 주는 아직 끝나지 않았으므로 지난 일요일이다.
 * 이렇게 잡으면 넣는 날이 언제든 끝난 주 26개가 꽉 차고, 6개월 창에도 거의 다 들어온다(implementation.md "시나리오 파일 규격").
 */
fun seedAnchor(today: LocalDate): LocalDate = today.minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

/**
 * 시드를 저장할 기록으로 바꾼다. 오래된 주행부터 나온다.
 *
 * - 날짜는 [seedAnchor]에서 `daysAgo`일 전이다. 기준일 [today]는 시연 진입의 `--es now`이고 없으면 오늘이다.
 * - 시드에는 시각이 없다. 같은 날의 주행은 8시부터 3시간 간격으로 둔다. 주행 기록 화면의 끝난 시각으로 보인다.
 * - 한 장은 시드에 없다. 장면과 기본 항목으로 [buildCard]가 만든다. 시드의 주행은 경로 안내와 주의 지점 이벤트가 있던 주행이다.
 */
fun seedRecords(seed: Seed, today: LocalDate, zone: ZoneId, c: Consts = Consts()): List<SeedRecord> {
    val anchor = seedAnchor(today)
    val perDay = HashMap<LocalDate, Int>()
    return seed.trips.sortedByDescending { it.daysAgo }.map { t ->
        val date = anchor.minusDays(t.daysAgo.toLong())
        val nth = perDay.merge(date, 1, Int::plus)!! - 1
        val time = LocalTime.of(minOf(23, 8 + 3 * nth), 0)
        val endAt = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        SeedRecord(
            endAt = endAt,
            km = t.km,
            scenes = t.scenes.map { it.copy(at = endAt) },
            basic = t.basic.map { it.copy(at = endAt) },
            exempts = t.exempts,
            card = buildCard(t.scenes, t.basic, guided = true, hadCaution = true, c = c),
        )
    }
}
