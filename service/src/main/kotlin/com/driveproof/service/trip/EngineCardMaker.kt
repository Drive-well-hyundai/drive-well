package com.driveproof.service.trip

import android.content.Context
import com.driveproof.data.BasicEventEntity
import com.driveproof.data.SceneEntity
import com.driveproof.data.TripEntity
import com.driveproof.data.TripRepository
import com.driveproof.engine.Card
import com.driveproof.engine.CardKeys
import com.driveproof.engine.LineKind
import com.driveproof.engine.renderCard
import com.driveproof.model.BasicKind
import com.driveproof.model.Exempt
import com.driveproof.model.Principle
import com.driveproof.scoring.BasicRow
import com.driveproof.scoring.History
import com.driveproof.scoring.SceneRow
import com.driveproof.scoring.TripRow
import com.driveproof.scoring.score
import com.driveproof.scoring.scoreLine
import com.driveproof.service.BuildConfig
import com.driveproof.source.DemoLog
import java.time.Duration

/** 입출력 스레드에서 종료할 주행과 누적 기록을 읽고, 저장할 문장을 만든다. */
internal class EngineCardMaker(context: Context, private val repo: TripRepository) {
    private val app = context.applicationContext

    fun make(trip: TripEntity, draft: Card, scenes: List<SceneEntity>, events: List<BasicEventEntity>): CardText? = try {
        val now = requireNotNull(trip.endAt)
        val coached = if (trip.source == "SEED") draft else {
            val previous = repo.db.trips().previousCoachingTrips(repo.profile, repo.demo, BuildConfig.MIN_KM, now, trip.id)
            coachingChange(draft, previous, scenes, repo.db.scenes()::byTrip, ::string)
        }
        val card = if (trip.hadGap) coached.withNote(CardKeys.NOTE_GAP) else coached
        val current = History(
            trips = listOf(TripRow(now, trip.km, card.lines.firstOrNull { it.kind == LineKind.FIX }?.topic)),
            scenes = scenes.mapNotNull { row ->
                val s = row.s ?: return@mapNotNull null
                val principle = Principle.valueOf(row.principle)
                val key = when (principle) {
                    Principle.SIGNAL -> "onDistanceM"
                    Principle.DECEL -> "maxDecel"
                    Principle.ZONE -> "maxKmh"
                    Principle.RISK -> return@mapNotNull null
                }
                SceneRow(now, principle, s, row.value(key))
            },
            basics = events.map { BasicRow(now, BasicKind.valueOf(it.kind), Exempt.valueOf(it.exempt)) },
        )
        val before = repo.history(now - Duration.ofDays(183).toMillis(), exclude = trip.id)
        val line = score(before + current, now)?.let { scoreLine(score(before, now), it) }
        val rendered = renderCard(card, ::string, line?.key, line?.args ?: emptyMap(), line?.total, line?.delta)
        CardText(rendered.json, rendered.firstLine)
    } catch (e: RuntimeException) {
        DemoLog.warn("한 장을 만들지 못했다: ${e.message}")
        null
    }

    private fun string(key: String): String {
        val id = app.resources.getIdentifier(key, "string", app.packageName)
        require(id != 0) { "strings.xml에 $key 가 없다" }
        return app.getString(id)
    }
}
