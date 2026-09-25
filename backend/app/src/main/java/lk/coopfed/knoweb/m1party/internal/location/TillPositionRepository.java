package lk.coopfed.knoweb.m1party.internal.location;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TillPositionRepository extends JpaRepository<TillPosition, UUID> {

    boolean existsByLocationIdAndPositionNo(UUID locationId, short positionNo);
}
