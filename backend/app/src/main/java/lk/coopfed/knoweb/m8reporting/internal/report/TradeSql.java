package lk.coopfed.knoweb.m8reporting.internal.report;

/**
 * The trading figures the reports, the tiles and the exception queue share, as SQL over the
 * trading projections, read in the caller's scope (row-level security decides whose rows they
 * are). Kept in one place so that a tile and the report it opens agree to the cent.
 */
final class TradeSql {

    private TradeSql() {}

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
                      order by document_id, owner_entity_id) i
               left join (select invoice_id,
                                 sum(case when kind = 'CREDIT' then 0 else amount end) as paid,
                                 sum(case when kind = 'CREDIT' then amount else 0 end) as credited
                            from reporting.trade_settlement_fact group by invoice_id) s
                      on s.invoice_id = i.document_id)
            """;

    /**
     * The exposure of each buyer with each seller whose credit limit M8 knows, from the latest
     * exposure warning of the relationship (exposure.warning.v1 carries the limit; M8 reads no
     * module's tables): 24A section 6.3's formula over the projections,
     *
     * <pre>
     *   open invoices + accepted orders not yet invoiced - receipts on account (not reversed)
     * </pre>
     *
     * with an order invoiced once an invoice bills a GRN of a delivery note that carried it, as
     * M4's ExposureCalculator counts it. Columns relationship_id, seller_entity_id,
     * buyer_entity_id, credit_limit, threshold_percent (the lowest threshold any warning of the
     * relationship crossed), warned_at (the latest warning), exposure.
     */
    static final String EXPOSURE =
            """
            (select w.relationship_id, w.seller_entity_id, w.buyer_entity_id, w.credit_limit,
                    w.threshold_percent, w.warned_at,
                    coalesce((select sum(inv.outstanding) from %1$s inv
                               where inv.seller_entity_id = w.seller_entity_id
                                 and inv.buyer_entity_id = w.buyer_entity_id), 0)
                  + coalesce((select sum(a.net) from reporting.trade_document_event a
                               where a.doc_type = 'ORDER' and a.event_kind = 'ACCEPTED'
                                 and a.seller_entity_id = w.seller_entity_id
                                 and a.buyer_entity_id = w.buyer_entity_id
                                 and not exists (select 1 from reporting.trade_document_event c
                                                  where c.document_id = a.document_id and c.event_kind = 'CANCELLED')
                                 and not exists (select 1 from reporting.trade_document_link dn
                                                   join reporting.trade_document_event g
                                                     on g.doc_type = 'GRN' and g.event_kind = 'CONFIRMED'
                                                    and g.reference_document_id = dn.document_id
                                                   join reporting.trade_document_link bill
                                                     on bill.link_kind = 'GRN' and bill.linked_document_id = g.document_id
                                                  where dn.link_kind = 'ORDER' and dn.linked_document_id = a.document_id)), 0)
                  - coalesce((select sum(r.unapplied) from reporting.trade_document_event r
                               where r.doc_type = 'PAYMENT' and r.event_kind in ('RECORDED', 'SETTLED')
                                 and r.seller_entity_id = w.seller_entity_id
                                 and r.buyer_entity_id = w.buyer_entity_id
                                 and not exists (select 1 from reporting.trade_document_event x
                                                  where x.doc_type = 'PAYMENT' and x.event_kind = 'REVERSED'
                                                    and x.reference_document_id
                                                        = case when r.event_kind = 'SETTLED'
                                                               then r.reference_document_id
                                                               else r.document_id end)), 0) as exposure
               from (select distinct on (relationship_id) relationship_id, seller_entity_id, buyer_entity_id,
                            credit_limit, occurred_at as warned_at,
                            min(threshold_percent) over (partition by relationship_id) as threshold_percent
                       from reporting.exposure_warning_event
                      where credit_limit is not null and credit_limit > 0
                      order by relationship_id, occurred_at desc) w)
            """
                    .formatted(INVOICES);

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
              order by g.document_id)
            """;
}
