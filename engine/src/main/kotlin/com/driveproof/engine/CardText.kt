package com.driveproof.engine

import com.driveproof.model.RULE_VERSION

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 저장할 한 장. [json]은 `cardJson`이고 [firstLine]은 주차 알림 본문에 쓰는 첫 문장이다 */
data class RenderedCard(val json: String, val firstLine: String?, val lines: List<String>, val notes: List<String>, val scoreLine: String?)

/**
 * 틀의 `{이름}` 자리에 값을 넣는다. 틀은 `strings.xml`의 [key] 문자열이다.
 * 값이 낱말 키(`card_word_*`)면 [strings]에서 그 낱말을 찾아 넣는다.
 * Android와 JVM 골든 테스트가 같은 함수를 쓴다. 문장 원본은 `strings.xml` 한 곳에만 있다.
 */
fun fillTemplate(key: String, args: Map<String, Any>, strings: (String) -> String): String {
    var text = strings(key)
    for ((name, value) in args) {
        val shown = if (value is String && value.startsWith("card_word_")) strings(value) else value.toString()
        text = text.replace("{$name}", shown)
    }
    require(!text.contains('{')) { "$key 에 채우지 못한 자리가 있다: $text" }
    return text
}

/**
 * 한 장을 문장으로 바꾸고 `cardJson`을 만든다(implementation.md "모듈 간 계약").
 *
 * @param strings `strings.xml`에서 키로 문자열을 찾는다
 * @param scoreKey 점수 줄의 키(`card_score_*`). 점수가 없으면 null
 * @param total 이 주행을 반영한 뒤의 총점 T. 소수 첫째 자리까지 저장한다
 * @param delta 반영 전 대비 변화(반올림한 점수의 차). 첫 주행이면 null
 */
fun renderCard(
    card: Card,
    strings: (String) -> String,
    scoreKey: String? = null,
    scoreArgs: Map<String, Any> = emptyMap(),
    total: Double? = null,
    delta: Int? = null,
    ruleVersion: String = RULE_VERSION,
): RenderedCard {
    val lineTexts = card.lines.map { fillTemplate(it.key, it.args, strings) }
    val noteTexts = card.notes.map { strings(it) }
    val scoreText = scoreKey?.let { fillTemplate(it, scoreArgs, strings) }

    val lines = card.lines.mapIndexed { i, l ->
        JsonObject(
            linkedMapOf(
                "kind" to JsonPrimitive(l.kind.name),
                "principle" to JsonPrimitive(l.topic.name),
                "key" to JsonPrimitive(l.key),
                "args" to JsonObject(l.args.mapValues { (_, v) -> if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) }),
                "estimated" to JsonPrimitive(l.estimated),
                "text" to JsonPrimitive(lineTexts[i]),
            ),
        )
    }
    val notes = card.notes.mapIndexed { i, key -> JsonObject(linkedMapOf("key" to JsonPrimitive(key), "text" to JsonPrimitive(noteTexts[i]))) }
    val score = JsonObject(
        linkedMapOf(
            "T" to (total?.let { JsonPrimitive(Math.round(it * 10) / 10.0) } ?: JsonNull),
            "delta" to (delta?.let { JsonPrimitive(it) } ?: JsonNull),
            "key" to (scoreKey?.let { JsonPrimitive(it) } ?: JsonNull),
            "text" to (scoreText?.let { JsonPrimitive(it) } ?: JsonNull),
        ),
    )
    val root = JsonObject(
        linkedMapOf(
            "lines" to JsonArray(lines),
            "notes" to JsonArray(notes),
            "score" to score,
            "ruleVersion" to JsonPrimitive(ruleVersion),
        ),
    )
    return RenderedCard(root.toString(), lineTexts.firstOrNull() ?: noteTexts.firstOrNull(), lineTexts, noteTexts, scoreText)
}
