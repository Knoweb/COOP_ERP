package lk.coopfed.knoweb.engine

interface RuleEvaluator<I, O> {

    fun evaluate(input: I): O
}