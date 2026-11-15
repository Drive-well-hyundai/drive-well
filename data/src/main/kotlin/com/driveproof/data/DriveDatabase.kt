package com.driveproof.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert fun insertOpen(trip: TripEntity): Long
    @Update fun update(trip: TripEntity)
    @Query("SELECT * FROM Trip WHERE id = :id") fun byId(id: Long): TripEntity?
    @Query("SELECT * FROM Trip WHERE status = 'OPEN' AND profileId = :profile AND isDemo = :demo ORDER BY id DESC LIMIT 1")
    fun findOpen(profile: String, demo: Boolean): TripEntity?
    @Query("SELECT * FROM Trip WHERE status = 'ENDED' AND cardJson IS NOT NULL AND seenAt IS NULL AND profileId = :profile AND isDemo = :demo ORDER BY endAt DESC, id DESC LIMIT 1")
    fun latestUnseen(profile: String, demo: Boolean): TripEntity?
    @Query("UPDATE Trip SET seenAt = :at WHERE id = :id AND status = 'ENDED' AND seenAt IS NULL AND endAt = :version")
    fun markSeen(id: Long, at: Long, version: Long)
    @Query("SELECT * FROM Trip WHERE status = 'ENDED' AND profileId = :profile AND isDemo = :demo ORDER BY endAt DESC, id DESC LIMIT :limit")
    fun list(profile: String, demo: Boolean, limit: Int): List<TripEntity>
    @Query("SELECT * FROM Trip WHERE status = 'ENDED' AND profileId = :profile AND isDemo = :demo AND source != 'SEED' AND km >= :minKm AND (endAt < :end OR (endAt = :end AND id < :id)) ORDER BY endAt DESC, id DESC LIMIT 3")
    fun previousCoachingTrips(profile: String, demo: Boolean, minKm: Double, end: Long, id: Long): List<TripEntity>
    @Query("SELECT * FROM Trip WHERE profileId = :profile AND isDemo = :demo ORDER BY endAt DESC, id DESC")
    fun observe(profile: String, demo: Boolean): Flow<List<TripEntity>>
    @Query("SELECT * FROM Trip WHERE profileId = :profile AND isDemo = :demo AND status != 'OPEN' AND endAt >= :from AND id != :exclude ORDER BY endAt")
    fun since(profile: String, demo: Boolean, from: Long, exclude: Long): List<TripEntity>
    @Query("DELETE FROM Trip WHERE endAt < :at") fun deleteBefore(at: Long)
    @Query("DELETE FROM Trip WHERE profileId = :profile AND (:onlyDemo = 0 OR isDemo = 1)") fun clear(profile: String, onlyDemo: Boolean)
    @Query("DELETE FROM Trip WHERE profileId = :profile AND isDemo = 1 AND source = 'SEED'") fun clearSeed(profile: String)
}

@Dao
interface SceneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(rows: List<SceneEntity>)
    @Query("DELETE FROM Scene WHERE tripId = :trip") fun clear(trip: Long)
    @Query("SELECT * FROM Scene WHERE tripId = :trip ORDER BY at, id") fun byTrip(trip: Long): List<SceneEntity>
    @Query("SELECT Scene.* FROM Scene JOIN Trip ON Scene.tripId = Trip.id WHERE Trip.profileId = :profile AND Trip.isDemo = :demo AND Trip.status = 'ENDED' AND Trip.endAt >= :at AND Trip.id != :exclude")
    fun since(profile: String, demo: Boolean, at: Long, exclude: Long): List<SceneEntity>
}

@Dao
interface BasicEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(rows: List<BasicEventEntity>)
    @Query("SELECT * FROM BasicEvent WHERE tripId = :trip ORDER BY at, id") fun byTrip(trip: Long): List<BasicEventEntity>
    @Query("SELECT BasicEvent.* FROM BasicEvent JOIN Trip ON BasicEvent.tripId = Trip.id WHERE Trip.profileId = :profile AND Trip.isDemo = :demo AND Trip.status != 'OPEN' AND Trip.endAt >= :at AND Trip.id != :exclude")
    fun since(profile: String, demo: Boolean, at: Long, exclude: Long): List<BasicEventEntity>
}

@Dao
interface ExemptEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(rows: List<ExemptEventEntity>)
    @Query("SELECT * FROM ExemptEvent WHERE profileId = :profile AND isDemo = :demo AND date >= :at AND mode = :mode ORDER BY date DESC")
    fun since(profile: String, demo: Boolean, at: Long, mode: String): List<ExemptEventEntity>
    @Query("DELETE FROM ExemptEvent WHERE date < :at") fun deleteBefore(at: Long)
    @Query("DELETE FROM ExemptEvent WHERE profileId = :profile AND (:onlyDemo = 0 OR isDemo = 1)") fun clear(profile: String, onlyDemo: Boolean)
    @Query("DELETE FROM ExemptEvent WHERE tripId IN (SELECT id FROM Trip WHERE profileId = :profile AND isDemo = 1 AND source = 'SEED')")
    fun clearSeed(profile: String)
    @Query("DELETE FROM ExemptEvent WHERE tripId = :trip AND date = :at") fun deleteEvent(trip: Long, at: Long)
}

@Dao
interface WeeklySummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(rows: List<WeeklySummaryEntity>)
    @Query("SELECT * FROM WeeklySummary WHERE profileId = :profile AND isDemo = :demo AND weekStart BETWEEN :from AND :to ORDER BY weekStart")
    fun range(profile: String, demo: Boolean, from: String, to: String): List<WeeklySummaryEntity>
    @Query("SELECT MAX(weekStart) FROM WeeklySummary WHERE profileId = :profile AND isDemo = :demo")
    fun lastWritten(profile: String, demo: Boolean): String?
    @Query("DELETE FROM WeeklySummary WHERE weekStart < :date") fun deleteBefore(date: String)
    @Query("DELETE FROM WeeklySummary WHERE profileId = :profile AND (:onlyDemo = 0 OR isDemo = 1)") fun clear(profile: String, onlyDemo: Boolean)
}

@Database(
    entities = [TripEntity::class, SceneEntity::class, BasicEventEntity::class, ExemptEventEntity::class, WeeklySummaryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DriveDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun scenes(): SceneDao
    abstract fun basics(): BasicEventDao
    abstract fun exempts(): ExemptEventDao
    abstract fun weeks(): WeeklySummaryDao

    companion object {
        @Volatile private var instance: DriveDatabase? = null

        fun get(context: Context): DriveDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, DriveDatabase::class.java, "drive-proof.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
