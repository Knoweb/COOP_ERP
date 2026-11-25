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

  it("allows FEDERATION_VIEW to see reporting reads while keeping mutating commands blocked", () => {
    const reportingPerms = [
      "rpt.report.run",
      "rpt.export.run",
      "rpt.target.view",
      "rpt.target.manage",
      "rpt.profit_center.view",
      "rpt.profit_center.manage",
      "rpt.asset.view",
      "rpt.asset.manage",
      "cat.import.run"
    ];
    const fedView = as(reportingPerms, "FEDERATION_VIEW");

    // FEDERATION_VIEW reads: explicit allowlist (.run) and convention (.view)
    expect(hasPermission(fedView, "rpt.report.run")).toBe(true);
    expect(hasPermission(fedView, "rpt.export.run")).toBe(true);
    expect(hasPermission(fedView, "rpt.target.view")).toBe(true);
    expect(hasPermission(fedView, "rpt.profit_center.view")).toBe(true);
    expect(hasPermission(fedView, "rpt.asset.view")).toBe(true);

    // FEDERATION_VIEW commands: blocked
    expect(hasPermission(fedView, "rpt.target.manage")).toBe(false);
    expect(hasPermission(fedView, "rpt.profit_center.manage")).toBe(false);
    expect(hasPermission(fedView, "rpt.asset.manage")).toBe(false);
    expect(hasPermission(fedView, "cat.import.run")).toBe(false);

    // OWN behavior remains unchanged: holds both reads and commands
    const own = as(reportingPerms, "OWN");
    expect(hasPermission(own, "rpt.report.run")).toBe(true);
    expect(hasPermission(own, "rpt.export.run")).toBe(true);
    expect(hasPermission(own, "rpt.target.view")).toBe(true);
    expect(hasPermission(own, "rpt.target.manage")).toBe(true);
    expect(hasPermission(own, "rpt.profit_center.manage")).toBe(true);
    expect(hasPermission(own, "rpt.asset.manage")).toBe(true);
    expect(hasPermission(own, "cat.import.run")).toBe(true);
  });

  it("asks for at least one of several permissions, and for nothing when the list is empty", () => {
    expect(hasAnyPermission(as(["hello.greeting.read"]), ["hello.greeting.register", "hello.greeting.read"])).toBe(true);
    expect(hasAnyPermission(as(["hello.greeting.read"]), ["hello.greeting.register"])).toBe(false);
    expect(hasAnyPermission(as([]), [])).toBe(true);
    // A read-only class still opens a module that has a read among its permissions.
    expect(hasAnyPermission(as(["gov.entity.view"], "FEDERATION_VIEW"), ["gov.entity.view", "gov.entity.register"])).toBe(true);
  });
});
