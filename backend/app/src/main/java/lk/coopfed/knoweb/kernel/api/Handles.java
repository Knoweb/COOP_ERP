package lk.coopfed.knoweb.kernel.api;

public interface Handles<C, R> {

    R handle(
            C command,
            ScopeContext context);
}