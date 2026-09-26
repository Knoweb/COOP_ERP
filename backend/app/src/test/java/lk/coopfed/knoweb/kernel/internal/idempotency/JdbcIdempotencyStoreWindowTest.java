package lk.coopfed.knoweb.kernel.internal.idempotency;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The key is unique per (user, key, UTC day): a window under a day would expire a key and then
 * refuse its reuse later the same day with a primary-key violation (#86).
 */
class JdbcIdempotencyStoreWindowTest {

    @Test
    void aWindowUnderADayIsRefusedAtStartup() {

        assertThatThrownBy(() -> new JdbcIdempotencyStore(mock(JdbcTemplate.class), 23))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 24");
    }

    @Test
    void aWindowOfADayOrMoreIsAccepted() {

        assertThatCode(() -> new JdbcIdempotencyStore(mock(JdbcTemplate.class), 24))
                .doesNotThrowAnyException();
        assertThatCode(() -> new JdbcIdempotencyStore(mock(JdbcTemplate.class), 48))
                .doesNotThrowAnyException();
    }
}
