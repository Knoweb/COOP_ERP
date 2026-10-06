package lk.coopfed.knoweb.m8reporting.internal.report;

/**
 * The trading figures the reports, the tiles and the exception queue share, as SQL over the
 * trading projections, read in the caller's scope (row-level security decides whose rows they
 * are). Kept in one place so that a tile and the report it opens agree to the cent.
 *
 * <p><b>Kinds that repeat</b> (wave 2, M8-03, decision D5). {@code trade_document_event} has a row
 * per event since m8reporting V0007, so a document can carry two rows of one kind: a second
 * dispute after a resolution, a second resolution. A query that adds up an amount or picks one
 * row of a kind reads {@link #EVENTS}, the latest event of each kind per document and owner by
 * (occurred_at, event_id); a query that only asks whether a kind exists may read the table, since
 * a second row changes no answer to "exists". Nothing here sums every row of a kind.
 */
final class TradeSql {

    private TradeSql() {}

    /**
     * The latest event of each kind on each document, per owner: one row where the table had one
     * before V0007, the later one where a kind repeated.
     */
    static final String EVENTS =
            """
            (select distinct on (document_id, event_kind, owner_entity_id) *
               from reporting.trade_document_event
              order by document_id, event_kind, owner_entity_id, occurred_at desc, event_id desc)
            """;

    /**
     * Every issued invoice the caller reads, once, with what was paid (receipts less the reversals
     * of bounced cheques) and credited against it, and what is still due (never below zero):
     * columns document_id, doc_number, business_date (the tax point), due_date, seller_entity_id,
     * buyer_entity_id, gross, paid, credited, outstanding.
     */
    static final String INVOICES =
            """
            (select i.document_id, i.doc_number, i.business_date, i.due_date, i.seller_entity_id,
                    i.buyer_entity_id, i.gross,
                    coalesce(s.paid, 0) as paid, coalesce(s.credited, 0) as credited,
                    greatest(coalesce(i.gross, 0) - coalesce(s.paid, 0) - coalesce(s.credited, 0), 0) as outstanding
               from (select distinct on (document_id) * from reporting.trade_document_event
                      where doc_type = 'INVOICE' and event_kind = 'ISSUED'
                      order by document_id, owner_entity_id, occurred_at desc, event_id desc) i
               left join (select invoice_id,
                                 sum(case when kind = 'CREDIT' then 0 else amount end) as paid,
                                 sum(case when kind = 'CREDIT' then amount else 0 end) as credited
                            from reporting.trade_settlement_fact group by invoice_id) s
                      on s.invoice_id = i.document_id)
            """;

    /**
     * The exposure of each buyer with each seller that has a credit limit above zero (wave 2,
     * M8-08, decision D6): the current limit is the latest {@code credit_limit.changed.v1} of the
     * pair (credit_limit_fact, the opening limit included), not a warning's, so a buyer that never
     * warned is listed too. 24A section 6.3's formula over the projections,
     *
     * <pre>
     *   open invoices + accepted orders not yet invoiced - receipts on account (not reversed)
     * </pre>
     *
     * with an order invoiced once an invoice bills a GRN of a delivery note that carried it, as
     * M4's ExposureCalculator counts it. Columns relationship_id (the row that carries the current
     * limit), seller_entity_id, buyer_entity_id, credit_limit, since (the latest warning after the
     * limit was set, otherwise when it was set), exposure. Which threshold the ratio reaches is
     * worked out by the caller from {@code trading.exposure_warn_thresholds}.
     */
    static final String EXPOSURE =
            """
            (select l.relationship_id, l.seller_entity_id, l.buyer_entity_id, l.credit_limit,
                    coalesce((select max(w.occurred_at) from reporting.exposure_warning_event w
                               where w.seller_entity_id = l.seller_entity_id
                                 and w.buyer_entity_id = l.buyer_entity_id
                                 and w.occurred_at >= l.occurred_at), l.occurred_at) as since,
                    coalesce((select sum(inv.outstanding) from %1$s inv
                               where inv.seller_entity_id = l.seller_entity_id
                                 and inv.buyer_entity_id = l.buyer_entity_id), 0)
                  + coalesce((select sum(a.net) from %2$s a
                               where a.doc_type = 'ORDER' and a.event_kind = 'ACCEPTED'
                                 and a.seller_entity_id = l.seller_entity_id
                                 and a.buyer_entity_id = l.buyer_entity_id
                                 and not exists (select 1 from reporting.trade_document_event c
                                                  where c.document_id = a.document_id and c.event_kind = 'CANCELLED')
                                 and not exists (select 1 from reporting.trade_document_link dn
                                                   join reporting.trade_document_event g
                                                     on g.doc_type = 'GRN' and g.event_kind = 'CONFIRMED'
                                                    and g.reference_document_id = dn.document_id
                                                   join reporting.trade_document_link bill
                                                     on bill.link_kind = 'GRN' and bill.linked_document_id = g.document_id
                                                  where dn.link_kind = 'ORDER' and dn.linked_document_id = a.document_id)), 0)
                  - coalesce((select sum(r.unapplied) from %2$s r
                               where r.doc_type = 'PAYMENT' and r.event_kind in ('RECORDED', 'SETTLED')
                                 and r.seller_entity_id = l.seller_entity_id
                                 and r.buyer_entity_id = l.buyer_entity_id
                                 and not exists (select 1 from reporting.trade_document_event x
                                                  where x.doc_type = 'PAYMENT' and x.event_kind = 'REVERSED'
                                                    and x.reference_document_id
                                                        = case when r.event_kind = 'SETTLED'
                                                               then r.reference_document_id
                                                               else r.document_id end)), 0) as exposure
               from (select distinct on (seller_entity_id, buyer_entity_id) relationship_id, seller_entity_id,
                            buyer_entity_id, credit_limit, occurred_at
                       from reporting.credit_limit_fact
                      order by seller_entity_id, buyer_entity_id, occurred_at desc, event_id desc) l
              where l.credit_limit > 0)
            """
                    .formatted(INVOICES, EVENTS);

    /**
     * Each confirmed GRN of a delivery with the date the seller committed to for the orders its
     * delivery note carried (the latest, when there are several): columns document_id,
     * seller_entity_id, buyer_entity_id, business_date (received), committed_eta (null when no
     * order named one).
     */
    static final String GRN_ETA =
            """
            (select distinct on (g.document_id) g.document_id, g.seller_entity_id, g.buyer_entity_id, g.business_date,
                    (select max(a.committed_eta) from reporting.trade_document_link dn
                       join reporting.trade_document_event a
                         on a.document_id = dn.linked_document_id and a.event_kind = 'ACCEPTED'
                      where dn.link_kind = 'ORDER' and dn.document_id = g.reference_document_id) as committed_eta
               from reporting.trade_document_event g
              where g.doc_type = 'GRN' and g.event_kind = 'CONFIRMED' and g.reference_document_id is not null
              order by g.document_id, g.occurred_at desc, g.event_id desc)
            """;
}
