package lk.coopfed.knoweb.m2catalogue.query;

import java.time.Instant;

/** One unit of the Federation's vocabulary (doc 22 section 3.2), for the SKU editor and UOM management. */
public record UomView(
        String uomCode,
        String nameEn,
        String nameSi,
        String nameTa,
        boolean weight,
        String description,
        String status,
        Instant createdAt) {

    public UomView(String uomCode, String nameEn, String nameSi, String nameTa, boolean weight) {
        this(uomCode, nameEn, nameSi, nameTa, weight, null, "ACTIVE", null);
    }
}
