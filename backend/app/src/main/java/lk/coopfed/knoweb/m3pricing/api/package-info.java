/**
 * Published surface of the pricing module: command records, event records, query interfaces
 * and the read-only views they return. Other modules may depend on this package and on
 * nothing else in pricing. Once another module depends on it, it is a contract: change it
 * by adding, never by editing or removing.
 */
@org.springframework.modulith.NamedInterface("api")
package lk.coopfed.knoweb.m3pricing.api;
