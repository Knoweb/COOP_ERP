package lk.coopfed.knoweb.m4trading.api;

import java.util.List;
import java.util.UUID;

/**
 * IssueInvoice (24A section 6): the seller invoices confirmed GRNs of one buyer under one
 * relationship, at the received quantities and the priced lines (the trade price each GRN line
 * carries), with VAT per line.
 */
public record IssueInvoice(List<UUID> grnIds) {}
