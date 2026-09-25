package lk.coopfed.knoweb.m1party.internal.user;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import lk.coopfed.knoweb.kernel.api.IdentityProviderClient;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * An identity provider for the handler tests that do not need the real one: it remembers every
 * call, hands out subjects and one-time passwords, and can be told to fail. The real provider
 * is exercised by {@link UsersAgainstTheProviderIntegrationTest}.
 */
class RecordingIdentityProvider implements IdentityProviderClient {

    record Call(String method, String subject) {}

    final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger passwords = new AtomicInteger();

    void reset() {
        calls.clear();
    }

    List<String> methodsFor(String subject) {
        return calls.stream()
                .filter(c -> subject.equals(c.subject()))
                .map(Call::method)
                .toList();
    }

    @Override
    public String createUser(ScopeContext ctx, UUID userId, UUID homeEntityId, String username, Locale language) {
        String subject = "subject-" + userId;
        calls.add(new Call("createUser", subject));
        return subject;
    }

    @Override
    public void disableUser(ScopeContext ctx, String subjectId) {
        calls.add(new Call("disableUser", subjectId));
    }

    @Override
    public TemporaryPassword setTemporaryPassword(ScopeContext ctx, String subjectId) {
        calls.add(new Call("setTemporaryPassword", subjectId));
        return new TemporaryPassword("Tmp" + passwords.incrementAndGet() + "xQ7kWz9pR");
    }

    @Override
    public void resetTotp(ScopeContext ctx, String subjectId) {
        calls.add(new Call("resetTotp", subjectId));
    }

    @Override
    public void revokeSessions(ScopeContext ctx, String subjectId) {
        calls.add(new Call("revokeSessions", subjectId));
    }
}
