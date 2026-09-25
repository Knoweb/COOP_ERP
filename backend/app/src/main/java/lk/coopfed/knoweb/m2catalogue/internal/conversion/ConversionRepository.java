package lk.coopfed.knoweb.m2catalogue.internal.conversion;

import org.springframework.data.repository.CrudRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

interface ConversionRepository extends CrudRepository<Conversion, UUID> {
    Optional<Conversion> findBySkuIdAndFromUomAndToUomAndValidTo(UUID skuId, String fromUom, String toUom, LocalDate validTo);
}
