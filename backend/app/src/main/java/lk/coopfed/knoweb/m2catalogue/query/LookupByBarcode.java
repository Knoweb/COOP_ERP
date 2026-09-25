package lk.coopfed.knoweb.m2catalogue.query;

import java.util.Optional;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

public interface LookupByBarcode {
    Optional<BarcodeResolution> resolve(String barcode, ScopeContext scope);
}
