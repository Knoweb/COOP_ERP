package lk.coopfed.knoweb.m1party.internal.entity;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntityRepository extends JpaRepository<Entity, UUID> {

    boolean existsByEntityCode(String entityCode);
}
