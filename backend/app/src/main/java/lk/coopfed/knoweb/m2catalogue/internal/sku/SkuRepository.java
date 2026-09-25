package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SkuRepository extends JpaRepository<Sku, UUID> {

    boolean existsBySkuCode(String skuCode);
}
