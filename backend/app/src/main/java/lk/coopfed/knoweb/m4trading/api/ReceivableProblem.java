package lk.coopfed.knoweb.m4trading.api;

/** A diagnostic problem identified during receivable bulk import (CAT-12). */
public record ReceivableProblem(int line, String reference, String message) {}
