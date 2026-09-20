import type { ReactNode } from "react";
import { useT } from "../i18n/useT";
import { hasAnyPermission } from "./permissions";
import { useSession } from "./session";

/**
 * Shows its children only to a user who holds at least one of the permissions; anybody else
 * gets a screen that says so. The shell wraps every route of a module in it (shell/modules/
 * assemble.tsx); a module uses it directly for a screen with a stricter rule than the module.
 *
 * Why a screen and not a blank page or a silent redirect: somebody who typed an address, or
 * followed a link a colleague sent, must learn that the page exists and that they may not
 * open it, so they know whom to ask. A blank page looks like a fault of the system.
 *
 * This is about what is shown. The server refuses the data anyway (shell/auth/permissions.ts).
 */
export function RequirePermission({ anyOf, children }: { anyOf: string[]; children: ReactNode }) {
  const session = useSession();
  const t = useT();

  if (session && hasAnyPermission(session, anyOf)) {
    return <>{children}</>;
  }
  return (
    <main className="shell-message" role="alert">
      <h1>{t("shell.not_allowed.title").text}</h1>
      <p>{t("shell.not_allowed.text").text}</p>
    </main>
  );
}
