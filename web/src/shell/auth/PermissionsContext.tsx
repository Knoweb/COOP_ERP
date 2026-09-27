// The resolved permission set of the signed-in user, read from the server once per token.
//
// GET /v1/session (openapi/session.yaml; decided 27 September 2026, CR-19A-8) answers who the
// caller is, the scopes they hold, and the permissions the kernel resolved for the active
// scope. The shell keeps that answer here; permissions.ts asks it. A renewed token reads it
// again (a role change or a revoked grant then shows within the token's lifetime), and the
// step-up replay's invalidation of every query re-reads it as well.
//
// Three states, and every screen must survive each: not read yet (null: show nothing that
// depends on a permission, say that the answer is on its way), read (the set), and failed (an
// empty set, said loudly by Navigation and RequirePermission through `failed`). Failed is the
// safe side: the server refuses what the user may not do anyway.

import { createContext, useContext, useMemo, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { useApiClient } from "../api/client";
import type { paths } from "../../generated/session";
import type { PermissionSet } from "./permissions";
import { useSession } from "./session";

export type PermissionsState = PermissionSet & {
  /** True when the read failed: the set is empty because nothing is known, not because nothing is held. */
  failed: boolean;
};

/** null while the set is being read; a provider is always there inside RequireLogin (App.tsx). */
export const PermissionsContext = createContext<PermissionsState | null | undefined>(undefined);

/** Put once around the pages by App, inside RequireLogin and the QueryClientProvider. */
export function PermissionsProvider({ children }: { children: ReactNode }) {
  const session = useSession();
  const api = useApiClient<paths>();
  const accessToken = session?.accessToken ?? null;

  const query = useQuery({
    queryKey: ["session", accessToken],
    enabled: accessToken !== null,
    // The set is re-read on a new token or when every query is invalidated (StepUpReplay),
    // not on focus: a permission changes rarely, and the server checks every request anyway.
    refetchOnWindowFocus: false,
    retry: 1,
    queryFn: async () => {
      const { data } = await api.GET("/v1/session");
      if (!data) {
        throw new Error("GET /v1/session answered without a body");
      }
      return data;
    }
  });

  const state = useMemo<PermissionsState | null>(() => {
    if (query.data) {
      return { policyClass: query.data.policyClass, permissions: query.data.permissions, failed: false };
    }
    if (query.isError) {
      return { policyClass: session?.policyClass ?? "NONE", permissions: [], failed: true };
    }
    return null;
  }, [query.data, query.isError, session?.policyClass]);

  return <PermissionsContext.Provider value={state}>{children}</PermissionsContext.Provider>;
}

/**
 * The resolved permission set, or null while it is being read. Outside a PermissionsProvider
 * (a unit test of a screen) it is null too: nothing permission-dependent shows, and the test
 * that wants a button wraps the screen in a provider or mocks this hook.
 */
export function usePermissions(): PermissionsState | null {
  const state = useContext(PermissionsContext);
  return state ?? null;
}
