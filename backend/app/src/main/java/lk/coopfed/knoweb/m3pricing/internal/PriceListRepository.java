package lk.coopfed.knoweb.m3pricing.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for price lists. Note what is missing: no method takes an entity id to filter
 * by. Row-level security limits every query to the rows the caller's scope may see, so
 * "all price lists" here always means "all price lists of the caller".
 */
interface PriceListRepository extends JpaRepository<PriceList, UUID> {

    boolean existsByTextEn(String textEn);

    /** A fixed cap keeps the template small; real modules page their lists (17A section 7). */
    List<PriceList> findTop100ByOrderByCreatedAtDesc();
}
