package lk.coopfed.knoweb.m4trading.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The invoice queries of doc 24 section 5.2; row-level security decides what a caller sees. */
public interface InvoiceQueries {

    Optional<InvoiceView> getInvoice(UUID invoiceId, ScopeContext scope);

    /** The invoices the caller's entity issued (SELLER) or received (BUYER), newest first. */
    List<InvoiceView> listInvoices(OrderQueries.Role role, ScopeContext scope);
}
