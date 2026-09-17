package lk.coopfed.knoweb.till.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_greeting")
data class GreetingEntity(
    @PrimaryKey
    val id: String,
    val textEn: String,
    val textSi: String?,
    val textTa: String?
)