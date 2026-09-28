# Languages and permissions

**Story.** As a shop-floor worker who reads Sinhala or Tamil and never English, I want every screen I touch to be in my own language, and I want to never be shown a button I am not allowed to press, so that I can do my job without training in a second language or a second set of rules for what I am allowed to do.

This file is short by design — it is a five-minute closer that names, out loud, two things the audience has already seen without being told: every screen so far has quietly been in a different language for a different user, and every screen has quietly hidden what that user cannot do. Use it after phase 4, once the audience has some documents on screen to look at again.

**Who signs in.** Any two users from `docs/DEMO.md`'s table who differ in language or scope — good pairs: `fed-steward` (English) and `d101-buyer` (Sinhala); or `fed-accounts` (entity-wide) and `m101-shop` (held to one shop).

## Steps

1. Open two browser windows (or one normal window and one private/incognito window, since two users cannot be signed in in the same profile at once — see `web/e2e/roles-and-scope.spec.ts`'s own comment on this). Sign in as `fed-steward` in one and `d102-buyer` (Tamil) in the other.
2. Put the two windows side by side, both on **Catalogue**. Point out: identical screen, identical data, one column of labels in English and the other in Tamil — the language comes from the signed-in user's own setting (`docs/DEMO.md`'s table column "Language"), not from a URL or a toggle the user has to find.
3. Open the same item's card in both windows — the item's own three stored names (English, Sinhala, Tamil) each show only in the viewer's own language; the underlying record is the same row.
4. Switch one window to `m101-shop` (a user held to one shop location only). Go to **Stock** and try to choose a different location from the **Location** field — point out either the field offers only the shop's own location, or an attempt to view another location is refused by the server, not merely hidden by the screen (row-level security, `AGENTS.md` idea 1 — "application code never filters by tenant itself").
5. Still as `m101-shop`, go to **Trading** — point out this user's navigation does not even offer the order desk or delivery notes that a buyer or seller role sees; a role's own permission set decides what the navigation shows at all, before row-level security decides what data a shown screen returns.
6. Optional: revisit the society register screen from phase 4 as a user without `gov.entity.register` — the register itself is visible, but "Register a society" is not offered, and the screen names why ("Your role may see the register but not register a society").

## What to point out

- **Three languages, one build**: nothing here is a second copy of the application — the same components render from the same message-id system regardless of language, and the backend refuses to build if any user-visible string is missing a translation (`AGENTS.md`, "Non-negotiable rules").
- **Two different rules, working together**: the ROLE decides what a screen *offers* (a button, a menu entry); the SCOPE decides what data the server *returns* for a request that is allowed at all. A user can hold a role that would let them dispatch deliveries in general, and still be refused at every warehouse but their own.
- **Permissions per role, not per person**: nobody hard-codes "Kamal cannot see Jaffna's stock" — `fed-stores` is scoped to FW01 as a property of the role and the location assignment, the same mechanism for every user in the table.

## What can go wrong

- **Both windows show the same language**: the two sign-ins landed in the same browser profile/session rather than two separate ones — use a private window, or two different browsers, so each keeps its own token.
- **A "forbidden" attempt shows a raw error instead of a clear message**: check which screen you tried it on — the design intends a plain refusal message for every guard, but a newer or less-travelled screen may not have one written yet; note it for the architect rather than treating it as the point of the demo.
- **The navigation item is not hidden, just does nothing when clicked**: this is a smaller gap than "invisible unless allowed" — say so plainly if the audience asks about it, rather than passing it off as a bug you can fix live.
