package lk.coopfed.knoweb.m1party.internal.entity;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.EntityRegistered;
import lk.coopfed.knoweb.m1party.api.RegisterEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "gov.entity.register")
class RegisterEntityHandler implements Handles<RegisterEntity, UUID> {

    static final String AUDIT_REGISTERED = "ENTITY_REGISTERED";

    private static final Set<String> VALID_TYPES = Set.of("FEDERATION", "DISTRIBUTOR", "MPCS");

    private static final Set<String> VALID_LANGUAGES = Set.of("en", "si", "ta");

    private final EntityRepository repository;
    private final AuditFacade audit;
    private final EventPublisher events;

    RegisterEntityHandler(EntityRepository repository, AuditFacade audit, EventPublisher events) {

        this.repository = repository;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RegisterEntity command, ScopeContext scope) {

        // 1. caller must be the Federation in an entity-wide OWN scope.
        Entity caller = FederationCaller.require(scope, repository);

        // 2. code unique.
        String entityCode = normalizeCode(command.entityCode());

        if (entityCode == null || entityCode.length() > 12) {
            throw new ProblemException("m1.entity.code_invalid");
        }

        /*
         * Under OWN RLS this pre-check sees rows visible to the caller.
         * The database UNIQUE constraint is the global/race-safe backstop.
         */
        if (repository.existsByEntityCode(entityCode)) {
            throw duplicateCode(entityCode);
        }

        // 3. type valid.
        String entityType = normalizeType(command.entityType());

        if (!VALID_TYPES.contains(entityType)) {
            throw new ProblemException(
                    "m1.entity.type_invalid", Map.of("entityType", String.valueOf(command.entityType())));
        }

        // 4. exactly one Federation.
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

        // 5. mutation: new entities always start ONBOARDING.
        Entity entity =
                Entity.register(Ids.next(), command, entityCode, entityType, language, (short) financialYearStartMonth);

        try {
            /*
             * Flush here so a global duplicate entity_code is translated before
             * audit/event publication. The transaction still rolls back as one unit.
             */
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            if (isUniqueViolation(ex)) {
                throw duplicateCode(entityCode);
            }

            throw ex;
        }

        // 6. audit.
        audit.record(AUDIT_REGISTERED, Subject.of("entity", entity.getId()), null, entity.auditState(), scope);

        // 7. committed-fact event/outbox.
        events.publish(new EntityRegistered(entity.getId(), entity.entityType()));

        return entity.getId();
    }

    private static String normalizeCode(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String normalizeLanguage(String value) {
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
