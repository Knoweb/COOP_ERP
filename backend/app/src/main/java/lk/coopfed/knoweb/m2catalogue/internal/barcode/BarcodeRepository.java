package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

interface BarcodeRepository extends CrudRepository<Barcode, UUID> {
    Optional<Barcode> findByBarcodeAndSymbologyAndStatus(String barcode, String symbology, String status);

    Optional<Barcode> findByBarcodeAndSymbologyAndOwnerEntityIdAndStatus(
            String barcode, String symbology, UUID ownerEntityId, String status);
}
