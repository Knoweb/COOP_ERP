import { describe, expect, it } from "vitest";
import { hasAnyPermission, hasPermission } from "./permissions";
import type { PolicyClass } from "./session";

// The set comes from the server (GET /v1/session; PermissionsContext.tsx). The rules tested here
// are the shell's own: a code the server did not return is not held, a user needs one of the
// permissions asked for, and a read-only class runs no command.
const as = (permissions: string[], policyClass: PolicyClass = "OWN") => ({ permissions, policyClass });

describe("what a user may see", () => {
  it("holds exactly the codes the server resolved", () => {
    expect(hasPermission(as(["hello.greeting.read"]), "hello.greeting.read")).toBe(true);
    expect(hasPermission(as(["hello.greeting.read", "hello.greeting.register"]), "hello.greeting.register")).toBe(true);
    expect(hasPermission(as(["hello.greeting.read"]), "hello.greeting.register")).toBe(false);
    expect(hasPermission(as([]), "hello.greeting.read")).toBe(false);
  });

  it("shows a read-only class its reads and no command, whatever the set says (19A section 3)", () => {
    // fed-admin of the dev realm: the FEDERATION_VIEW class, which sees every entity and runs
    // nothing; the server returns every read of every slice for it.
    const reads = ["gov.entity.view", "hello.greeting.read", "gov.external.grant"];
    expect(hasPermission(as(reads, "FEDERATION_VIEW"), "gov.entity.view")).toBe(true);
    expect(hasPermission(as(reads, "FEDERATION_VIEW"), "hello.greeting.read")).toBe(true);
    expect(hasPermission(as(reads, "FEDERATION_VIEW"), "gov.entity.register")).toBe(false);
    // A code that names a command is not offered to a read-only class even when the server
    // returned it (gov.external.grant is the read of the register and the command that fills it).
    expect(hasPermission(as(reads, "FEDERATION_VIEW"), "gov.external.grant")).toBe(false);
    expect(hasPermission(as(["hello.greeting.register"], "PARTY"), "hello.greeting.register")).toBe(false);
    expect(hasPermission(as(["hello.greeting.register"], "EXTERNAL_TIMEBOXED"), "hello.greeting.register")).toBe(false);
    expect(hasPermission(as(["hello.greeting.register"], "NONE"), "hello.greeting.register")).toBe(false);
    expect(hasPermission(as(["hello.greeting.register"]), "hello.greeting.register")).toBe(true);
  });

  it("asks for at least one of several permissions, and for nothing when the list is empty", () => {
    expect(hasAnyPermission(as(["hello.greeting.read"]), ["hello.greeting.register", "hello.greeting.read"])).toBe(true);
    expect(hasAnyPermission(as(["hello.greeting.read"]), ["hello.greeting.register"])).toBe(false);
    expect(hasAnyPermission(as([]), [])).toBe(true);
    // A read-only class still opens a module that has a read among its permissions.
    expect(hasAnyPermission(as(["gov.entity.view"], "FEDERATION_VIEW"), ["gov.entity.view", "gov.entity.register"])).toBe(true);
  });
});
