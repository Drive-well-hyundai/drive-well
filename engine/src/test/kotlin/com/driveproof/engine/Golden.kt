package com.driveproof.engine

import com.driveproof.model.Gear
import com.driveproof.model.GearPos
import com.driveproof.model.Signal
import com.driveproof.model.playScenario
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** 골든 테스트가 같이 쓰는 도구. 골든은 `tools/golden/export.js`가 demo-sim.js에서 뽑은 `golden/`이다 */
internal object Golden {
    /** PT 시나리오 5개 */
    val scenarios = listOf("s1", "s2", "s3", "s4", "s5")

    /** 경계 시나리오 6종(implementation.md "기준 구현과 골든 테스트") */
    val boundaries = listOf("b1", "b2", "b3", "b4", "b5", "b6")

    val ids = scenarios + boundaries

    /**
     * 시나리오를 재생한 신호. PT 시나리오는 demo 빌드 에셋이고 경계 시나리오는 골든 폴더에 있다.
     * Gradle은 모듈 디렉터리(app/engine)에서 테스트를 돌린다
     */
    fun signals(id: String): List<Signal> = playScenario(
        if (id in boundaries) Golden::class.java.getResource("/scenarios/$id.json")?.readText() ?: error("경계 시나리오가 없다: $id")
        else File("../app/src/demo/assets/$id.json").readText(),
    )

    fun ticks(id: String): JsonObject = read("$id.ticks.json")

    fun result(id: String): JsonObject = read("$id.json")

    fun JsonObject.col(name: String): JsonArray = getValue(name).jsonArray

    /** 앱 모듈들의 values 폴더에 있는 xml에서 card_ 문자열을 모은다. 어느 모듈에 있든 찾는다 */
    val cardStrings: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        File("..").listFiles { f -> f.isDirectory }!!.forEach { module ->
            File(module, "src/main/res/values").listFiles { f -> f.extension == "xml" }?.forEach { xml ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml).getElementsByTagName("string")
                for (i in 0 until nodes.length) {
                    val name = nodes.item(i).attributes.getNamedItem("name").nodeValue
                    if (name.startsWith("card_")) out[name] = nodes.item(i).textContent
                }
            }
        }
        out
    }

    fun cardText(key: String): String = cardStrings[key] ?: error("strings.xml에 $key 가 없다")

    private fun read(name: String): JsonObject {
        val text = Golden::class.java.getResource("/$name")?.readText() ?: error("골든이 없다: $name")
        return Json.parseToJsonElement(text).jsonObject
    }

    /**
     * `:service`가 부르는 순서대로 엔진을 돌린다.
     * 주행 중 기어가 P로 바뀌면 그 신호를 넘기기 전에 `end`를 부른다(TripService의 Parked 처리).
     */
    fun run(engine: TripEngine, signals: List<Signal>): TripResult {
        var result: TripResult? = null
        var gear = GearPos.P
        for (s in signals) {
            if (s is Gear) {
                if (result == null && gear != GearPos.P && s.gear == GearPos.P) result = engine.end(s.t)
                gear = s.gear
            }
            engine.onSignal(s)
        }
        // 마지막 칸까지 계산한다. 칸은 더 늦은 신호가 와야 계산된다
        engine.onSignal(Gear(signals.last().t + 100, gear))
        return result ?: error("기어 P로 끝나지 않는 시나리오다")
    }
}
