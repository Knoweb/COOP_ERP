package lk.coopfed.knoweb.m2catalogue.query;

import lk.coopfed.knoweb.kernel.api.ScopeContext;
import java.util.Optional;

public interface LookupByBarcode {
    Optional<BarcodeResolution> resolve(String barcode, ScopeContext scope);
}
