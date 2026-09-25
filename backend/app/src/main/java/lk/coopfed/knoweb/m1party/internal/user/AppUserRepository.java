package lk.coopfed.knoweb.m1party.internal.user;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads and writes under the caller's row-level security: a user outside the scope is not found. */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {}
