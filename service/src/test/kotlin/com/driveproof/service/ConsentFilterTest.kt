package com.driveproof.service

import com.driveproof.data.RecordingConsent
import com.driveproof.engine.TripEngine
import com.driveproof.model.Consts
import com.driveproof.model.Principle
import com.driveproof.model.playScenario
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ConsentFilterTest {
    @Test fun turningOffControlsRemovesSignalEvaluationFromScenarioOne() {
        val json = File("../app/src/demo/assets/s1.json").readText()
        val signals = playScenario(json)
        fun result(consent: RecordingConsent) = TripEngine(Consts(minKm = 0.2), consent.controls).run {
            signals.filter(consent::allows).forEach(::onSignal)
            end(signals.last().t)
        }
        assertTrue(result(RecordingConsent()).scenes.any { it.principle == Principle.SIGNAL })
        assertFalse(result(RecordingConsent(controls = false)).scenes.any { it.principle == Principle.SIGNAL })
    }
}
