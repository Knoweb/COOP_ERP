import { useSession } from "../auth/session";
import { useT } from "../i18n/useT";
import { useScope } from "./useScope";

/**
 * The strip at the top of every page that says who is acting for which entity, at which
 * location, in which policy class (doc 30 section 2.2). Its purpose is to prevent work in the
 * wrong scope: an accountant of two societies must see, before typing, which one this is.
 *
 * Rules it follows:
 *   - it is always there. A user with no scope does not get an empty strip but a warning,
 *     because the server answers such a user with empty lists, and an empty list reads as
 *     "there is nothing", not as "you may see nothing";
 *   - colour is never the only signal (doc 30): the policy class is written out in words and
 *     the warning starts with the word "Warning". The CSS classes only add emphasis;
 *   - every text is a message id.
 *
 * There is no scope switcher: a user has one scope for now (see ScopeContext.tsx).
 */
export function ScopeBanner() {
  const session = useSession();
  const scope = useScope();
  const t = useT();

  if (!scope.seesData) {
    return (
      <div className="scope-banner scope-banner--warning" role="alert">
        <strong>{t("shell.scope.none.title").text}</strong> {t("shell.scope.none.text").text}
      </div>
    );
  }

  // No entity names before M1: "Entity …00000002" is what can honestly be shown.
  const entity = scope.entityName ?? t("shell.scope.entity_unnamed", undefined, { shortId: scope.entityShortId }).text;
  // A location narrows the scope to one shop or warehouse; without one it is the whole entity.
  const location = scope.locationId ?? t("shell.scope.location_all").text;

  return (
    <div
      className={scope.policyClass === "OWN" ? "scope-banner" : "scope-banner scope-banner--not-own"}
      role="region"
      aria-label={t("shell.scope.label").text}
    >
      <span className="scope-banner__who">
        {t("shell.scope.acting_for", undefined, { user: session?.displayName ?? "", entity }).text}
      </span>
      <span className="scope-banner__location">{location}</span>
      <span className="scope-banner__class">
        {t("shell.scope.access").text} <strong>{t(`shell.scope.class.${scope.policyClass}`).text}</strong>
      </span>
    </div>
  );
}
