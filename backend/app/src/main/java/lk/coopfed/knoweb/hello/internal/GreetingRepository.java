package lk.coopfed.knoweb.hello.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for greetings. Note what is missing: no method takes an entity id to filter
 * by. Row-level security limits every query to the rows the caller's scope may see, so
 * "all greetings" here always means "all greetings of the caller".
 */
interface GreetingRepository extends JpaRepository<Greeting, UUID> {

    boolean existsByTextEn(String textEn);

    /** A fixed cap keeps the template small; real modules page their lists (17A section 7). */
    List<Greeting> findTop100ByOrderByCreatedAtDesc();
}
