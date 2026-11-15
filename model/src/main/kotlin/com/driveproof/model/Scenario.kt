package com.driveproof.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * 재생 시나리오. 규격은 implementation.md "시나리오 파일 규격"이다.
 * [start]는 차량 시각(예: `2026-11-10T18:30`)이고 없으면 null이다.
 */
data class Scenario(
    val name: String,
    val naviRoute: Boolean,
    val start: String?,
    val events: List<ScenarioEvent>,
)

/** 시나리오의 한 줄. 바뀐 값만 있다. 없는 값은 null이다 */
data class ScenarioEvent(
    val t: Long,
    val gear: GearPos? = null,
    val speedKmh: Double? = null,
    val turnSignal: Blink? = null,
    val brake: Boolean? = null,
    val steeringDeg: Double? = null,
    val tbt: TbtKey? = null,
    /** `caution` 필드가 있는 줄이다. `null`이면 [caution]이 null이다 */
    val hasCaution: Boolean = false,
    val caution: CautionKey? = null,
    val telltale: Map<String, Lamp> = emptyMap(),
    val speedLimitKmh: Int? = null,
)

/** `tbt`. [type]이 null이면 앞 줄의 종류를 잇는다 */
data class TbtKey(val type: String?, val distance: Double)

/** `caution`. [type]과 [limit]이 null이면 앞 줄의 값을 잇는다 */
data class CautionKey(val type: String?, val remain: Double, val limit: Int?)

/** 시나리오 JSON을 읽는다. 형식이 틀리면 [IllegalArgumentException]을 던진다 */
fun parseScenario(json: String): Scenario {
    val root = try {
        Json.parseToJsonElement(json).jsonObject
    } catch (e: RuntimeException) {
        throw IllegalArgumentException("시나리오 JSON을 읽지 못했다: ${e.message}", e)
    }
    try {
        val route = root.str("route") ?: "navi"
        require(route == "navi" || route == "vehicle") { "route는 navi 또는 vehicle이다: $route" }
        val events = (root["events"] ?: throw IllegalArgumentException("events가 없다")).jsonArray.map { parseEvent(it.jsonObject) }
        require(events.isNotEmpty()) { "events가 비었다" }
        return Scenario(
            name = root.str("name") ?: "",
            naviRoute = route == "navi",
            start = root.str("start"),
            events = events.sortedBy { it.t }, // 같은 시각은 적힌 순서를 지킨다
        )
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: RuntimeException) {
        throw IllegalArgumentException("시나리오 형식이 틀렸다: ${e.message}", e)
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun parseEvent(o: JsonObject): ScenarioEvent {
    val t = (o["t"] ?: throw IllegalArgumentException("t가 없는 줄이 있다")).jsonPrimitive.long
    require(t >= 0) { "t는 0 이상이다: $t" }
    val cau = o["caution"]
    return ScenarioEvent(
        t = t,
        gear = o.str("gear")?.let { GearPos.valueOf(it) },
        speedKmh = o["speed_kmh"]?.jsonPrimitive?.double,
        turnSignal = o.str("turn_signal")?.let { Blink.valueOf(it) },
        brake = o["brake"]?.jsonPrimitive?.boolean,
        steeringDeg = o["steering_deg"]?.jsonPrimitive?.double,
        tbt = o["tbt"]?.jsonObject?.let { b ->
            TbtKey(b.str("type"), (b["distance"] ?: throw IllegalArgumentException("tbt.distance가 없다 (t=$t)")).jsonPrimitive.double)
        },
        hasCaution = cau != null,
        caution = (cau as? JsonObject)?.let { c ->
            CautionKey(
                c.str("type"),
                (c["remain"] ?: throw IllegalArgumentException("caution.remain이 없다 (t=$t)")).jsonPrimitive.double,
                c["limit"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int,
            )
        },
        telltale = (o["telltale"] as? JsonObject)?.mapValues { Lamp.valueOf(it.value.jsonPrimitive.content) } ?: emptyMap(),
        speedLimitKmh = o["speed_limit_kmh"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int,
    )
}
