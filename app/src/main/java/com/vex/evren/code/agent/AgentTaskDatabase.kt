package com.vex.evren.code.agent

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "agent_tasks")
data class AgentTaskRecord(
    @PrimaryKey val id: String,
    val model: String,
    val request: String,
    val targetsJson: String,
    val maxTokens: Int,
    val maxSteps: Int,
    val fullAccess: Boolean,
    val contextLength: Int?,
    val status: String,
    val progress: String,
    val checkpointJson: String?,
    val answer: String?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Dao
interface AgentTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(task: AgentTaskRecord)
    @Query("SELECT * FROM agent_tasks WHERE id = :id LIMIT 1") suspend fun get(id: String): AgentTaskRecord?
    @Query("SELECT * FROM agent_tasks ORDER BY updatedAt DESC") fun observeAll(): Flow<List<AgentTaskRecord>>
    @Query("SELECT * FROM agent_tasks WHERE status = 'RUNNING' ORDER BY updatedAt DESC LIMIT 1") suspend fun latestRunning(): AgentTaskRecord?
    @Query("UPDATE agent_tasks SET status = :status, progress = :progress, updatedAt = :now WHERE id = :id")
    suspend fun setState(id: String, status: String, progress: String, now: Long = System.currentTimeMillis())
    @Query("UPDATE agent_tasks SET progress = :progress, checkpointJson = :checkpoint, updatedAt = :now WHERE id = :id")
    suspend fun saveCheckpoint(id: String, progress: String, checkpoint: String?, now: Long = System.currentTimeMillis())
    @Query("UPDATE agent_tasks SET status = :status, progress = :progress, answer = :answer, error = :error, checkpointJson = :checkpoint, updatedAt = :now WHERE id = :id")
    suspend fun finish(id: String, status: String, progress: String, answer: String?, error: String?, checkpoint: String?, now: Long = System.currentTimeMillis())
}

@Database(entities = [AgentTaskRecord::class], version = 1, exportSchema = false)
abstract class AgentTaskDatabase : RoomDatabase() {
    abstract fun tasks(): AgentTaskDao
    companion object {
        @Volatile private var instance: AgentTaskDatabase? = null
        fun get(context: Context): AgentTaskDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AgentTaskDatabase::class.java, "evren_agent_tasks.db")
                .build().also { instance = it }
        }
    }
}