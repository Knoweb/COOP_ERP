/**
 * Published surface of the trading module: command records, event records, the value types
 * they carry, and the questions M4 asks of modules not built yet. Other modules may depend on
 * this package and on {@code query}, and on nothing else in trading. Once another module depends
 * on it, it is a contract: change it by adding, never by editing or removing.
 */
@org.springframework.modulith.NamedInterface("api")
package lk.coopfed.knoweb.m4trading.api;
