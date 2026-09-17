package lk.coopfed.knoweb.till.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        GreetingEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TillDatabase : RoomDatabase() {

    abstract fun greetingDao(): GreetingDao
}