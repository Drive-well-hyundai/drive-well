package com.driveproof.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.driveproof.model.RULE_VERSION
import org.json.JSONObject

const val DEFAULT_PROFILE = "default"

@Entity(tableName = "Trip", indices = [Index("profileId", "isDemo", "endAt")])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String = DEFAULT_PROFILE,
    val startAt: Long,
    val endAt: Long? = null,
    val odoStart: Double? = null,
    val km: Double = 0.0,
    val kmSource: String = "INTEGRAL",
    val naviRatio: Double? = null,
    val limitKnownKm: Double = 0.0,
    val status: String = "OPEN",
    val hadGap: Boolean = false,
    val isDemo: Boolean,
    val source: String,
    val ruleVersion: String = RULE_VERSION,
    val cardJson: String? = null,
    val seenAt: Long? = null,
)

@Entity(
    tableName = "Scene",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId", "at", "principle", "kind", unique = true)],
)
data class SceneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val principle: String,
    val kind: String,
    val at: Long,
    val valuesJson: String,
    val criterion: Double?,
    val s: Double?,
    val path: String?,
    val estimated: Boolean,
) {
    fun value(name: String): Double? = runCatching {
        val json = JSONObject(valuesJson)
        if (json.isNull(name)) null else json.getDouble(name)
    }.getOrNull()
}

@Entity(
    tableName = "BasicEvent",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId", "at", "kind", unique = true)],
)
data class BasicEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val kind: String,
    val at: Long,
    val value: Double,
    val exempt: String,
)

@Entity(
    tableName = "ExemptEvent",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("tripId", "date", unique = true), Index("profileId", "isDemo", "date")],
)
data class ExemptEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long?,
    val profileId: String = DEFAULT_PROFILE,
    val date: Long,
    val decel: Double,
    val reason: String,
    val mode: String,
    val isDemo: Boolean,
)

@Entity(tableName = "WeeklySummary", primaryKeys = ["weekStart", "profileId", "isDemo"])
data class WeeklySummaryEntity(
    val weekStart: String,
    val profileId: String = DEFAULT_PROFILE,
    val isDemo: Boolean,
    val summaryJson: String,
    val ruleVersion: String,
)
