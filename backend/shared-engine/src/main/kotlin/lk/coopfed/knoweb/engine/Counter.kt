package lk.coopfed.knoweb.engine

data class Counter(
    val value: Long
) {

    init {
        require(value >= 0) {
            "Counter value cannot be negative"
        }
    }

    fun next(): Counter =
        Counter(value + 1)
}