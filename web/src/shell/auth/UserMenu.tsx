import { useT } from "../i18n/useT";
import { useSession } from "./session";

/** Who is signed in, and the way out. Part of the shell's header on every page. */
export function UserMenu() {
  const session = useSession();
  const t = useT();

  if (!session) {
    return null;
  }

  const initials = session.displayName
    .split(" ")
    .map(n => n[0])
    .join("")
    .substring(0, 2)
    .toUpperCase();

  return (
    <div className="user-menu">
      <div className="user-menu__profile">
        <div className="user-menu__avatar" aria-hidden="true">{initials}</div>
        <div className="user-menu__info">
          <span className="user-menu__name">{session.displayName}</span>
        </div>
      </div>
      <button type="button" className="btn-signout" onClick={session.signOut}>
        <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path>
          <polyline points="16 17 21 12 16 7"></polyline>
          <line x1="21" y1="12" x2="9" y2="12"></line>
        </svg>
        {t("shell.auth.sign_out").text}
      </button>
    </div>
  );
}
