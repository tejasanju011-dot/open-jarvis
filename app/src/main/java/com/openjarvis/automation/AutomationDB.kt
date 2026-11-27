
package com.openjarvis.automation

import android.content.Context
import androidx.room.*
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val command: String,
    val scheduleType: String,
    val scheduleHour: Int = 0,
    val scheduleMinute: Int = 0,
    val scheduleDayOfWeek: Int = 0,
    val scheduleIntervalMs: Long = 0,
    val scheduleAtMs: Long = 0,
    val enabled: Boolean = true,
    val lastRun: Long? = null,
    val lastResult: String? = null,
    val runCount: Int = 0
)

@Dao
interface AutomationDao {

    @Query("SELECT * FROM automations ORDER BY name")
    suspend fun getAll(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getById(id: String): AutomationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(automation: AutomationEntity)

    @Update
    suspend fun update(automation: AutomationEntity)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: String)
}

// Convert database entity into app model
fun AutomationEntity.toAutomation(): AutomationManager.Automation {

    val schedule = when (scheduleType) {
        "daily" -> AutomationManager.AutomationSchedule.Daily(
            scheduleHour,
            scheduleMinute
        )

        "weekly" -> AutomationManager.AutomationSchedule.Weekly(
            scheduleDayOfWeek,
            scheduleHour,
            scheduleMinute
        )

        "interval" -> AutomationManager.AutomationSchedule.Interval(
            scheduleIntervalMs
        )

        "once" -> AutomationManager.AutomationSchedule.Once(
            scheduleAtMs
        )

        else -> AutomationManager.AutomationSchedule.Daily(
            scheduleHour,
            scheduleMinute
        )
    }

    return AutomationManager.Automation(
        id = id,
        name = name,
        command = command,
        schedule = schedule,
        enabled = enabled,
        lastRun = lastRun,
        lastResult = lastResult,
        runCount = runCount
    )
}

// Convert app model into database entity
fun AutomationManager.Automation.toEntity(): AutomationEntity {

    var type = "daily"
    var hour = 0
    var minute = 0
    var day = 0
    var interval = 0L
    var at = 0L

    when (val s = schedule) {

        is AutomationManager.AutomationSchedule.Daily -> {
            type = "daily"
            hour = s.hour
            minute = s.minute
        }

        is AutomationManager.AutomationSchedule.Weekly -> {
            type = "weekly"
            day = s.dayOfWeek
            hour = s.hour
            minute = s.minute
        }

        is AutomationManager.AutomationSchedule.Interval -> {
            type = "interval"
            interval = s.intervalMs
        }

        is AutomationManager.AutomationSchedule.Once -> {
            type = "once"
            at = s.atMs
        }
    }

    return AutomationEntity(
        id = id,
        name = name,
        command = command,
        scheduleType = type,
        scheduleHour = hour,
        scheduleMinute = minute,
        scheduleDayOfWeek = day,
        scheduleIntervalMs = interval,
        scheduleAtMs = at,
        enabled = enabled,
        lastRun = lastRun,
        lastResult = lastResult,
        runCount = runCount
    )
}

@Database(
    entities = [AutomationEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AutomationDB : RoomDatabase() {

    abstract fun automationDao(): AutomationDao

    companion object {

        @Volatile
        private var INSTANCE: AutomationDB? = null

        fun getInstance(context: Context): AutomationDB {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutomationDB::class.java,
                    "automations.db"
                )
                    .build()
                    .also {
                        INSTANCE = it
                    }
            }
        }
    }
}

class AutomationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {

            val id = inputData.getString("automation_id")
                ?: return@withContext Result.failure()

            try {
                val dao = AutomationDB
                    .getInstance(applicationContext)
                    .automationDao()

                val automation = dao.getById(id)
                    ?: return@withContext Result.failure()

                val updated = automation.copy(
                    lastRun = System.currentTimeMillis(),
                    lastResult = "success",
                    runCount = automation.runCount + 1
                )

                dao.update(updated)

                Result.success()

            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure()
            }
        }
}
