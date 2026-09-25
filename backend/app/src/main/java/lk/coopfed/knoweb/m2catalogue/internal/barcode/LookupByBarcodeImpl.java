package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.BarcodeResolution;
import lk.coopfed.knoweb.m2catalogue.query.LookupByBarcode;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class LookupByBarcodeImpl implements LookupByBarcode {

    private final BarcodeRepository repository;

    LookupByBarcodeImpl(BarcodeRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<BarcodeResolution> resolve(String barcodeString, ScopeContext scope) {
        Optional<Barcode> found = repository.findByBarcodeAndSymbologyAndStatus(barcodeString, Barcode.SYMBOLOGY_FACTORY, Barcode.STATUS_ACTIVE)
            .or(() -> repository.findByBarcodeAndSymbologyAndOwnerEntityIdAndStatus(barcodeString, Barcode.SYMBOLOGY_INTERNAL, scope.activeScope().entityId(), Barcode.STATUS_ACTIVE));

        if (found.isPresent()) {
            Barcode b = found.get();
            return Optional.of(new BarcodeResolution(b.getSkuId(), b.getUom(), b.getBatchId()));
        }

        if (barcodeString.startsWith("01") && barcodeString.length() > 16) {
            String gtin = barcodeString.substring(2, 16);
            
            Optional<Barcode> gtinBarcode = repository.findByBarcodeAndSymbologyAndStatus(gtin, Barcode.SYMBOLOGY_FACTORY, Barcode.STATUS_ACTIVE);
            if (gtinBarcode.isPresent()) {
                Barcode b = gtinBarcode.get();
                // Extended GS1 parsing (lot and expiry) would lookup the exact batch here.
                // Since this is M2-E04 scaffolding, we resolve to the base SKU.
                return Optional.of(new BarcodeResolution(b.getSkuId(), b.getUom(), null));
            }
        }

        return Optional.empty();
    }
}
