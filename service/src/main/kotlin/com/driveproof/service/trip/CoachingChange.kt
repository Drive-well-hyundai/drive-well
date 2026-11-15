package com.driveproof.service.trip

import com.driveproof.data.SceneEntity
import com.driveproof.data.TripEntity
import com.driveproof.engine.Card
import com.driveproof.engine.CardLine
import com.driveproof.engine.LineKind
import com.driveproof.engine.fillTemplate
import com.driveproof.model.CardTopic
import org.json.JSONObject
import kotlin.math.roundToInt

/** 직전 세 번에 같은 고칠 점이 표시됐을 때 이번 주행의 변화량을 보여 준다. */
internal fun coachingChange(
    draft: Card,
    previous: List<TripEntity>,
    currentScenes: List<SceneEntity>,
    scenesOf: (Long) -> List<SceneEntity>,
    string: (String) -> String,
): Card {
    if (previous.size != 3) return draft
    val fixes = previous.map { shownFix(it.cardJson, string) }
    val fix = fixes.first() ?: return draft
    if (fixes.any { it != fix }) return draft
    val topic = fix.first
    val (key, field) = when (topic) {
        CardTopic.SIGNAL -> "card_change_signal" to "onDistanceM"
        CardTopic.DECEL -> "card_change_decel" to "maxDecel"
        CardTopic.ZONE -> "card_change_zone" to "maxKmh"
        else -> return draft
    }
    val runs = (previous.map { scenesOf(it.id) } + listOf(currentScenes)).map { scenes ->
        scenes.filter { it.principle == topic.name && it.s != null }
    }
    val values = runs.map { scenes ->
        val measured = scenes.mapNotNull { it.value(field) }
        if (measured.isEmpty()) {
            if (topic == CardTopic.SIGNAL && scenes.isNotEmpty() && scenes.all { it.s == 0.0 }) return@map null
            return draft
        }
        if (topic == CardTopic.SIGNAL) measured.average() else measured.max()
    }
    val baseline = values.take(3)
    val before = baseline.map { it ?: 0.0 }.average().roundToInt()
    val after = values.last()?.roundToInt()
    val args = if (topic == CardTopic.SIGNAL) {
        fun distance(m: Int?): String = if (m == null) "card_word_signal_not_on" else "${m}m"
        mapOf("before" to distance(before.takeUnless { baseline.all { it == null } }), "after" to distance(after))
    } else mapOf("before" to before, "after" to requireNotNull(after))
    val change = CardLine(
        LineKind.FIX, topic, key,
        args,
        estimated = runs.flatten().all { it.estimated },
    )
    return draft.copy(lines = draft.lines.filter { it.kind != LineKind.FIX }.take(1) + change)
}

private fun shownFix(json: String?, string: (String) -> String): Pair<CardTopic, String>? = runCatching {
    val lines = JSONObject(json ?: return null).getJSONArray("lines")
    val fix = (0 until lines.length()).asSequence().map(lines::getJSONObject)
        .firstOrNull { it.optString("kind") == "FIX" } ?: return null
    if (fix.optString("key").startsWith("card_change_")) return null
    val text = fix.getString("text").takeIf(String::isNotBlank) ?: return null
    // 같은 문구 버전에서 비교한다. 화면에서는 주행 당시 저장된 문장을 그대로 쓴다.
    val comparable = fix.optJSONObject("args")?.let { args ->
        runCatching { fillTemplate(fix.getString("key"), args.keys().asSequence().associateWith(args::get), string) }.getOrNull()
    } ?: text
    CardTopic.valueOf(fix.getString("principle")) to comparable
}.getOrNull()
