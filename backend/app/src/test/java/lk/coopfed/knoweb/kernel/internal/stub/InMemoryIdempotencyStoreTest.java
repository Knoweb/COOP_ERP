package lk.coopfed.knoweb.kernel.internal.stub;

import lk.coopfed.knoweb.kernel.api.IdempotencyStore.Key;
import lk.coopfed.knoweb.kernel.api.IdempotencyStore.StoredResult;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Doc 14 §1.3 and doc 18 Part E: the same key replays the same result; a reused key with another body is rejected. */
class InMemoryIdempotencyStoreTest {

    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
    private final UUID user = Ids.next();

    @Test
    void unknownKeyHasNoResult() {
        assertTrue(store.find(new Key("k1", user, "hash-a")).isEmpty());
    }

    @Test
    void storedResultIsReplayedForTheSameKeyAndRequest() {
        Key key = new Key("k1", user, "hash-a");
        store.store(key, new StoredResult(201, "{\"id\":\"1\"}"));

        Optional<StoredResult> replay = store.find(new Key("k1", user, "hash-a"));

        assertTrue(replay.isPresent());
        assertEquals(201, replay.get().status());
        assertEquals("{\"id\":\"1\"}", replay.get().body());
    }

    @Test
    void sameKeyWithADifferentRequestIsRejected() {
        store.store(new Key("k1", user, "hash-a"), new StoredResult(201, "{}"));

        ProblemException problem = assertThrows(
                ProblemException.class,
                () -> store.find(new Key("k1", user, "hash-b")));

        assertEquals("idempotency.request_mismatch", problem.messageId());
    }

    @Test
    void keysAreScopedPerUser() {
        store.store(new Key("k1", user, "hash-a"), new StoredResult(201, "{}"));

        assertTrue(store.find(new Key("k1", Ids.next(), "hash-a")).isEmpty());
    }

    @Test
    void blankKeysAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Key(" ", user, "hash-a"));
        assertThrows(IllegalArgumentException.class, () -> new Key("k1", user, ""));
    }
}
