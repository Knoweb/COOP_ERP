import { useContext } from "react";
import { ScopeContext } from "./ScopeContext";
import type { Scope } from "./ScopeContext";

/**
 * The scope the user is acting in: `const scope = useScope(); scope.entityId ...`.
 *
 * It returns the ACTIVE scope only, which is what a screen needs. The list of all scopes of
 * the user is the business of the future scope switcher, which reads ScopeContext itself.
 */
export function useScope(): Scope {
  const state = useContext(ScopeContext);
  if (!state) {
    throw new Error("useScope() was called outside <ScopeProvider>; the shell's layout provides it (router.tsx)");
  }
  return state.active;
}
