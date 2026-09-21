package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/**
 * Separation of duties: the helper a handler calls when the same person must not both ask
 * for something and approve it (19A section 3).
 *
 * <p>The pairs themselves are M1's data ({@code sod_pair} in the {@code security} schema, doc
 * 18); the kernel is what a handler asks. A pair in INSTANCE mode forbids one person doing
 * both on the same document; a pair in ROLE mode forbids one person holding both permissions
 * at all, which M1 enforces when roles are authored.
 *
 * <pre>
 *   sod.assertDistinct(ctx, "inv.writeoff.request", "inv.writeoff.approve", requesterId);
 * </pre>
 *
 * <p>Ticket K-03b implements it. Until then this interface has no implementation and nothing
 * injects it.
 *
 * <p>19A section 3 also names {@code Sod.roleModeConflict(role)} and, beside it,
 * {@code Grant.withinGrantor(ctx, permissions)} and {@code Grant.lastAdminGuard(entity)}.
 * Their parameters are M1 types that no document defines yet, so they are not declared here.
 */
public interface Sod {

    /**
     * Refuses when the caller is the person who made the request being approved.
     *
     * @param ctx               the approver's scope; {@code ctx.userId()} is the approver
     * @param requestPermission the permission the request was made under
     * @param approvePermission the permission the approval is being made under
     * @param requesterId       the user who made the request
     * @throws ProblemException {@code sod.same_person} when the two permissions are an
     *                          INSTANCE pair and the requester is the caller
     */
    void assertDistinct(ScopeContext ctx, String requestPermission, String approvePermission, UUID requesterId);
}
