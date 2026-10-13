package lk.coopfed.archfixtures.kernel.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * A kernel API that hands any caller another entity's scope, as #334's ScopedExecution did
 * (CR-19A-14 rejected it). The job-scope rule reports every module that calls it.
 */
public interface MintsAScope {

    ScopeContext own(UUID entityId);
}
