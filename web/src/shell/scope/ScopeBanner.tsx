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

  // Split acting string to style user and entity names strongly
  const rawActing = t("shell.scope.acting_for", undefined, { user: "[[USER]]", entity: "[[ENTITY]]" }).text;
  const actingParts = rawActing.split(/(\[\[USER\]\]|\[\[ENTITY\]\])/);

  return (
    <div
      className={scope.policyClass === "OWN" ? "scope-banner" : "scope-banner scope-banner--not-own"}
      role="region"
      aria-label={t("shell.scope.label").text}
    >
      <div className="scope-acting">
        <div className="scope-acting__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="20" height="20" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
            <rect x="4" y="2" width="16" height="20" rx="2" ry="2"></rect>
            <path d="M9 22v-4h6v4"></path>
            <path d="M8 6h.01"></path>
            <path d="M16 6h.01"></path>
            <path d="M12 6h.01"></path>
            <path d="M12 10h.01"></path>
            <path d="M12 14h.01"></path>
            <path d="M16 10h.01"></path>
            <path d="M16 14h.01"></path>
            <path d="M8 10h.01"></path>
            <path d="M8 14h.01"></path>
          </svg>
        </div>
        <div className="scope-acting__text">
          {actingParts.map((part, i) => {
            if (part === "[[USER]]") return <strong key={i} className="scope-user">{session?.displayName ?? ""}</strong>;
            if (part === "[[ENTITY]]") return <strong key={i} className="scope-entity">{entity}</strong>;
            return part ? <span key={i} className="scope-muted">{part}</span> : null;
          })}
        </div>
      </div>
    </div>
  );
}
