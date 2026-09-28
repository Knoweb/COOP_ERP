import { describe, expect, it } from "vitest";
import { ApiProblem } from "../../shell/api/client";
import { navItemsFor } from "../../shell/modules/assemble";
import type { Role } from "./adminApi";
import { assignableRoles, refusalOf, userActions } from "./adminView";
import { partyModule } from "./module";

const wiring = { canManage: true, t: (id: string) => id, busy: false, onPassword: () => {}, onSecondFactor: () => {}, onDeactivate: () => {} };

describe("the actions of a user's card", () => {
  it("offer the password first for a pending user", () => {
    const actions = userActions({ status: "PENDING", userKind: "BACK_OFFICE" }, wiring);
    expect(actions.map((a) => a.id)).toEqual(["password", "second-factor", "deactivate"]);
    expect(actions[0].primary).toBe(true);
  });

  it("are none for a deactivated user, or without the permission", () => {
    expect(userActions({ status: "DEACTIVATED", userKind: "BACK_OFFICE" }, wiring)).toEqual([]);
    expect(userActions({ status: "ACTIVE", userKind: "BACK_OFFICE" }, { ...wiring, canManage: false })).toEqual([]);
  });

  it("disable the password of a till-only user, with the reason", () => {
    const [password] = userActions({ status: "ACTIVE", userKind: "TILL" }, wiring);
    expect(password.disabledReason).toBe("party.user.password.till_only");
  });
});

describe("the roles offered for an assignment", () => {
  const base = { nameEn: "r", roleClass: "OWN", templateUpdated: false, version: 1, permissions: [] } as unknown as Role;
  it("are the entity's own roles in force", () => {
    const roles = [
      { ...base, roleId: "own", ownerEntityId: "e1", template: false, status: "ACTIVE" },
      { ...base, roleId: "template", ownerEntityId: null, template: true, status: "ACTIVE" },
      { ...base, roleId: "retired", ownerEntityId: "e1", template: false, status: "RETIRED" }
    ] as Role[];
    expect(assignableRoles(roles).map((r) => r.roleId)).toEqual(["own"]);
  });
});

describe("a refusal", () => {
  it("is marked when it is a separation-of-duties rule", () => {
    const sod = refusalOf(new ApiProblem({ status: 422, code: "m1.assignment.sod_conflict", title: "two duties" }), "x");
    expect(sod).toEqual({ text: "two duties", code: "m1.assignment.sod_conflict", sod: true });
    expect(refusalOf(new ApiProblem({ status: 422, code: "m1.assignment.exists", title: "held" }), "x").sod).toBe(false);
    expect(refusalOf(new Error("network"), "fallback").text).toBe("fallback");
  });
});

describe("the party module's navigation", () => {
  const labels = (permissions: string[]) =>
    navItemsFor([partyModule], { policyClass: "OWN", permissions }).map((item) => item.labelId);

  it("hides the entries the user cannot open instead of showing them inert", () => {
    expect(labels(["gov.entity.view"])).toEqual(["party.nav"]);
    expect(labels(["gov.user.view"])).toEqual(["party.nav.users"]);
    expect(labels(["gov.entity.view", "gov.user.manage", "gov.role.manage", "gov.external.grant"])).toEqual([
      "party.nav",
      "party.nav.users",
      "party.nav.roles",
      "party.nav.grants"
    ]);
    expect(labels(["cat.sku.view"])).toEqual([]);
  });

  it("follows the class rule: the Federation view sees the reads only", () => {
    const items = navItemsFor([partyModule], { policyClass: "FEDERATION_VIEW", permissions: ["gov.entity.view", "gov.role.manage"] });
    expect(items.map((item) => item.labelId)).toEqual(["party.nav"]);
  });
});
