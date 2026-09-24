package lk.coopfed.knoweb.kernel.api;

/**
 * One kind of document, as the registry {@code kernel.document_type} describes it (doc 18
 * part C; flags as decided in 24B). Seeded from {@code seed/kernel/document-types.yaml}.
 *
 * @param seriesScope     the finest scope the type numbers at
 * @param bilateral       a counterparty is mandatory
 * @param fiscal          gaplessness and immutability are a compliance matter
 * @param offlineIssuable a till may issue it without the server
 * @param owningModule    whose validator and state machine apply
 */
public record DocumentType(
        String code,
        String nameEn,
        String nameSi,
        String nameTa,
        SeriesScope seriesScope,
        String issuerRole,
        boolean bilateral,
        boolean fiscal,
        boolean offlineIssuable,
        String owningModule) {}
