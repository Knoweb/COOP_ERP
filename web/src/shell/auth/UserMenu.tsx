import { useT } from "../i18n/useT";
import { useSession } from "./session";

/** Who is signed in, and the way out. Part of the shell's header on every page. */
export function UserMenu() {
  const session = useSession();
  const t = useT();

  if (!session) {
    return null;
  }
  return (
    <div className="user-menu">
      <span className="user-menu__name">{session.displayName}</span>
      <button type="button" onClick={session.signOut}>
        {t("shell.auth.sign_out").text}
      </button>
    </div>
  );
}
