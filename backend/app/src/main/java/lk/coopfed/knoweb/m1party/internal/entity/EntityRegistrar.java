package lk.coopfed.knoweb.m1party.internal.entity;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m1party.api.RegisterEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * The rules of registering an entity, and the entity they produce (21A section 6,
 * RegisterEntity): the code, the type, the one Federation, the name, the language, the
 * financial year. {@link RegisterEntityHandler} applies them to one command and
 * {@code BulkRegisterEntitiesHandler} to every row of a file, so a rule lives once.
 *
 * <p>What this class does not do, on purpose: write, audit or publish. The build's rules
 * (ArchitectureTests: only a {@code @CommandHandler} writes to the database, and every
 * handler audits and publishes) keep those three in the handler, where a reader of the handler
 * sees the whole of a command. A handler never calls another handler either: the kernel's
 * command interceptor claims the request's idempotency key around each one.
 */
@Component
public class EntityRegistrar {

    public static final int CODE_MAX_LENGTH = 12;
    public static final Set<String> VALID_TYPES = Set.of("FEDERATION", "DISTRIBUTOR", "MPCS");
    public static final Set<String> VALID_LANGUAGES = Set.of("en", "si", "ta");

    private final EntityRepository repository;

    public EntityRegistrar(EntityRepository repository) {
        this.repository = repository;
    }

    /** True when an entity with this code exists; under the Federation's scope that is every entity. */
    public boolean codeExists(String entityCode) {
        return repository.existsByEntityCode(entityCode);
    }

    /**
     * The entity a valid command registers, in ONBOARDING status, or a {@link ProblemException}
     * naming the rule the command breaks. Nothing is written.
     */
    public Entity prepare(RegisterEntity command) {

        // 1. code unique.
        String entityCode = normalizeCode(command.entityCode());

        if (entityCode == null || entityCode.length() > CODE_MAX_LENGTH) {
            throw new ProblemException("m1.entity.code_invalid");
        }

        /*
         * Under OWN RLS this pre-check sees rows visible to the caller.
         * The database UNIQUE constraint is the global/race-safe backstop.
         */
        if (repository.existsByEntityCode(entityCode)) {
            throw duplicateCode(entityCode);
        }

        // 2. type valid.
        String entityType = normalizeType(command.entityType());

        if (!VALID_TYPES.contains(entityType)) {
            throw new ProblemException(
                    "m1.entity.type_invalid", Map.of("entityType", String.valueOf(command.entityType())));
        }

        // 3. exactly one Federation.
        // A legitimate caller is already the existing Federation.
        if (Entity.TYPE_FEDERATION.equals(entityType)) {
            throw new ProblemException("m1.entity.federation_exists");
        }

        if (command.legalNameEn() == null || command.legalNameEn().isBlank()) {
            throw new ProblemException("m1.entity.legal_name_required");
        }

        String language = normalizeLanguage(command.defaultLanguage());

        if (!VALID_LANGUAGES.contains(language)) {
            throw new ProblemException(
                    "m1.entity.default_language_invalid",
                    Map.of("defaultLanguage", String.valueOf(command.defaultLanguage())));
        }

        int financialYearStartMonth = command.financialYearStartMonth() == null ? 1 : command.financialYearStartMonth();

        if (financialYearStartMonth < 1 || financialYearStartMonth > 12) {
            throw new ProblemException(
                    "m1.entity.financial_year_start_month_invalid",
                    Map.of("financialYearStartMonth", financialYearStartMonth));
        }

        // 4. new entities always start ONBOARDING.
        return Entity.register(Ids.next(), command, entityCode, entityType, language, (short) financialYearStartMonth);
    }

    /**
     * Translates the unique-index violation of a race (two registrations of one code at once)
     * into the problem the pre-check answers; anything else is rethrown as it is.
     */
    public static ProblemException translate(DataIntegrityViolationException ex, Entity entity) {
        if (isUniqueViolation(ex)) {
            return duplicateCode(entity.entityCode());
        }
        throw ex;
    }

    public static String normalizeCode(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public static String normalizeType(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    public static String normalizeLanguage(String value) {
        if (value == null || value.isBlank()) {
            return "en";
        }

        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static ProblemException duplicateCode(String entityCode) {
        return new ProblemException("m1.entity.code_duplicate", Map.of("entityCode", entityCode));
    }

    private static boolean isUniqueViolation(Throwable error) {
        Throwable current = error;

        while (current != null) {
            if (current instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {

                return true;
            }

            current = current.getCause();
        }

        return false;
    }
}
