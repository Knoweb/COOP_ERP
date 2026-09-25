package lk.coopfed.knoweb.m1party.internal.location;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    boolean existsByOwnerEntityIdAndLocationCode(UUID ownerEntityId, String locationCode);
}
