package lk.coopfed.knoweb.m1party.internal.location;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TillPositionRepository extends JpaRepository<TillPosition, UUID> {

    boolean existsByLocationIdAndPositionNo(UUID locationId, short positionNo);

    /**
     * The position, locked for the rest of the transaction. Always taken after the lock on its
     * location ({@link LocationRepository#findByIdForUpdate}), in every handler, so that two
     * handlers never wait for each other's lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from TillPosition p where p.id = :id")
    Optional<TillPosition> findByIdForUpdate(@Param("id") UUID id);
}
