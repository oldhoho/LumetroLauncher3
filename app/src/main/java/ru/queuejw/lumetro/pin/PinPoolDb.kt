package ru.queuejw.lumetro.pin

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update

@Entity(
    tableName = "PinPoolItem",
    indices = [
        Index(value = ["category"]),
        Index(value = ["createdAt"]),
        Index(value = ["pinnedAt"]),
        Index(value = ["category", "contentHash"]),
    ]
)
data class PinPoolModel(
    @PrimaryKey val id: String,
    val type: String,
    val text: String?,
    val imagePath: String?,
    val createdAt: Long,
    val pinnedAt: Long,
    val reminderAt: Long,
    val category: String,
    val contentHash: String = "",
)

@Dao
interface PinPoolDao {
    @Query("SELECT * FROM PinPoolItem ORDER BY pinnedAt DESC, createdAt DESC")
    suspend fun getAll(): List<PinPoolModel>

    @Query(
        "SELECT * FROM PinPoolItem WHERE category = :category ORDER BY pinnedAt DESC, createdAt DESC"
    )
    suspend fun getByCategory(category: String): List<PinPoolModel>

    @Query("SELECT * FROM PinPoolItem WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PinPoolModel?

    @Query(
        "SELECT id FROM PinPoolItem WHERE category = :category AND contentHash = :hash AND contentHash != '' LIMIT 1"
    )
    suspend fun findIdByCategoryAndHash(category: String, hash: String): String?

    @Query("SELECT * FROM PinPoolItem WHERE category = :category AND pinnedAt <= 0")
    suspend fun getUnpinnedByCategory(category: String): List<PinPoolModel>

    @Query(
        "SELECT * FROM PinPoolItem WHERE category = :category AND pinnedAt <= 0 AND createdAt < :expireBefore"
    )
    suspend fun getExpiredUnpinnedByCategory(
        category: String,
        expireBefore: Long,
    ): List<PinPoolModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: PinPoolModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(models: List<PinPoolModel>)

    @Update
    suspend fun update(model: PinPoolModel)

    @Query("DELETE FROM PinPoolItem WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM PinPoolItem WHERE category = :category AND pinnedAt <= 0")
    suspend fun deleteUnpinnedByCategory(category: String)

    @Query("DELETE FROM PinPoolItem")
    suspend fun deleteAll()
}

@Database(entities = [PinPoolModel::class], version = 1, exportSchema = false)
abstract class PinPoolDatabase : RoomDatabase() {
    abstract fun pinPoolDao(): PinPoolDao

    companion object {
        @Volatile
        private var instance: PinPoolDatabase? = null

        fun get(context: Context): PinPoolDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PinPoolDatabase::class.java,
                    "pin_pool.db"
                ).build().also { instance = it }
            }
    }
}

fun getPinPoolDao(context: Context): PinPoolDao = PinPoolDatabase.get(context).pinPoolDao()
