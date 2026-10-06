/**
 * Published surface of the customers module: command records and event records. Other modules
 * may depend on this package and on {@code query}, and on nothing else in customers. An event
 * carries ids and amounts only, never a name, phone number or NIC (27A, "Read this first").
 */
@org.springframework.modulith.NamedInterface("api")
package lk.coopfed.knoweb.m7customers.api;
