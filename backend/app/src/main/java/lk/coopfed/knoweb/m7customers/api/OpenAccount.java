package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Open the caller's society's credit account for a customer (27A section 6, OpenAccount).
 *
 * @param creditLimit zero or more; a limit above zero needs the customer's NIC
 * @param termsDays   null for the configured default
 * @param offlineCap  null for the configured default
 * @param nic         the National Identity Card number, as typed; only its hash and last four
 *                    characters are kept
 */
public record OpenAccount(
        UUID customerId, BigDecimal creditLimit, Integer termsDays, BigDecimal offlineCap, String nic) {}
