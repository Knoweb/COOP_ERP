package lk.coopfed.archfixtures.m1party;

import org.springframework.data.repository.Repository;

import java.util.UUID;

/** A repository for the fixtures below to write through. Correct in itself. */
public interface FixtureRepository extends Repository<Object, UUID> {

    Object save(Object entity);

    Object findById(UUID id);
}
