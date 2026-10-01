package lk.coopfed.knoweb.m2catalogue.query;

import java.util.UUID;

public record ImageView(
        UUID imageId,
        String barcode,
        String status,
        String objectKeyFull,
        String objectKeyThumb) {}
