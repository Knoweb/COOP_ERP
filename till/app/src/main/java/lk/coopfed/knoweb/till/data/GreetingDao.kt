package lk.coopfed.knoweb.till.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GreetingDao {

    @Query("SELECT * FROM cached_greeting LIMIT 1")
    suspend fun first(): GreetingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(greeting: GreetingEntity)
}