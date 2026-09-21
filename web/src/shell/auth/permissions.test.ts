import { describe, expect, it } from "vitest";
import { hasAnyPermission, hasPermission } from "./permissions";

// The map behind these answers is temporary (19A K-03 replaces it); the behaviour tested here
// is what stays: unknown means no, and a user needs one of the permissions asked for.
describe("what a user may see", () => {
  it("gives a development role the permissions written down for it", () => {
    expect(hasPermission({ roles: ["cashier"] }, "hello.greeting.read")).toBe(true);
    expect(hasPermission({ roles: ["mpcs-admin"] }, "hello.greeting.register")).toBe(true);
  });

  it("does not give a role a permission that is not written down for it", () => {
    expect(hasPermission({ roles: ["cashier"] }, "hello.greeting.register")).toBe(false);
  });

  it("gives nothing to a role it does not know, and nothing to a user without roles", () => {
    expect(hasPermission({ roles: ["superuser"] }, "hello.greeting.read")).toBe(false);
    expect(hasPermission({ roles: [] }, "hello.greeting.read")).toBe(false);
  });

  it("lets the development federation administrator see every module, also one scaffolded a minute ago", () => {
    expect(hasPermission({ roles: ["fed-admin"] }, "cat.sku.create")).toBe(true);
  });

  it("asks for at least one of several permissions, and for nothing when the list is empty", () => {
    expect(hasAnyPermission({ roles: ["cashier"] }, ["hello.greeting.register", "hello.greeting.read"])).toBe(true);
    expect(hasAnyPermission({ roles: ["cashier"] }, ["hello.greeting.register"])).toBe(false);
    expect(hasAnyPermission({ roles: [] }, [])).toBe(true);
  });
});
