package lk.coopfed.knoweb.m1party.internal.location;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    boolean existsByOwnerEntityIdAndLocationCode(UUID ownerEntityId, String locationCode);

    /**
     * The location, locked for the rest of the transaction ({@code SELECT ... FOR UPDATE}). The
     * handlers that change a location or one of its positions load it this way, so that a
     * SetPrimaryTill and a RetireTillPosition of the same shop run one after the other and the
     * second sees what the first committed (the review of M1-05). Empty when the caller's
     * row-level security does not see the row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Location l where l.id = :id")
    Optional<Location> findByIdForUpdate(@Param("id") UUID id);
}
