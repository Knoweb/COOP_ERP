package lk.coopfed.knoweb.engine

interface PriceResolver<I, O> {

    fun resolve(input: I): O
}