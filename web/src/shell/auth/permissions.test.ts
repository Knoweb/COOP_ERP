import { describe, expect, it } from "vitest";
import { hasAnyPermission, hasPermission } from "./permissions";
import type { PolicyClass } from "./session";

// The map behind these answers is temporary (the server's resolved permission set replaces
// it); the behaviour tested here is what stays: unknown means no, a user needs one of the
// permissions asked for, and a read-only class runs no command.
const as = (roles: string[], policyClass: PolicyClass = "OWN") => ({ roles, policyClass });

describe("what a user may see", () => {
  it("gives a development role the permissions written down for it", () => {
    expect(hasPermission(as(["cashier"]), "hello.greeting.read")).toBe(true);
    expect(hasPermission(as(["mpcs-admin"]), "hello.greeting.register")).toBe(true);
  });

  it("does not give a role a permission that is not written down for it", () => {
    expect(hasPermission(as(["cashier"]), "hello.greeting.register")).toBe(false);
  });

  it("gives nothing to a role it does not know, and nothing to a user without roles", () => {
    expect(hasPermission(as(["superuser"]), "hello.greeting.read")).toBe(false);
    expect(hasPermission(as([]), "hello.greeting.read")).toBe(false);
  });

  it("lets the development federation administrator see every module, also one scaffolded a minute ago", () => {
    expect(hasPermission(as(["fed-admin"]), "cat.sku.create")).toBe(true);
  });

  it("shows a read-only class its reads and no command, whatever its roles say (19A section 3)", () => {
    // fed-admin of the dev realm: the FEDERATION_VIEW class, which sees every entity and runs nothing.
    expect(hasPermission(as(["fed-admin"], "FEDERATION_VIEW"), "gov.entity.view")).toBe(true);
    expect(hasPermission(as(["fed-admin"], "FEDERATION_VIEW"), "hello.greeting.read")).toBe(true);
    expect(hasPermission(as(["fed-admin"], "FEDERATION_VIEW"), "gov.entity.register")).toBe(false);
    expect(hasPermission(as(["fed-admin"], "FEDERATION_VIEW"), "gov.entity.suspend")).toBe(false);
    expect(hasPermission(as(["mpcs-admin"], "PARTY"), "hello.greeting.register")).toBe(false);
    expect(hasPermission(as(["mpcs-admin"], "EXTERNAL_TIMEBOXED"), "hello.greeting.register")).toBe(false);
    expect(hasPermission(as(["mpcs-admin"], "NONE"), "hello.greeting.register")).toBe(false);
  });

  it("asks for at least one of several permissions, and for nothing when the list is empty", () => {
    expect(hasAnyPermission(as(["cashier"]), ["hello.greeting.register", "hello.greeting.read"])).toBe(true);
    expect(hasAnyPermission(as(["cashier"]), ["hello.greeting.register"])).toBe(false);
    expect(hasAnyPermission(as([]), [])).toBe(true);
    // A read-only class still opens a module that has a read among its permissions.
    expect(hasAnyPermission(as(["fed-admin"], "FEDERATION_VIEW"), ["gov.entity.view", "gov.entity.register"])).toBe(true);
  });
});
