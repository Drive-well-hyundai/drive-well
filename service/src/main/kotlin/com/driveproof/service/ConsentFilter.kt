package com.driveproof.service

import com.driveproof.data.RecordingConsent
import com.driveproof.model.Brake
import com.driveproof.model.Caution
import com.driveproof.model.NaviState
import com.driveproof.model.RoadLimit
import com.driveproof.model.Signal
import com.driveproof.model.Steering
import com.driveproof.model.Tbt
import com.driveproof.model.Telltale
import com.driveproof.model.TurnSignal

internal fun RecordingConsent.allows(signal: Signal) = when (signal) {
    is TurnSignal, is Brake, is Steering -> controls
    is Tbt, is NaviState, is Caution, is RoadLimit -> navigation
    is Telltale -> telltales
    else -> true
}
