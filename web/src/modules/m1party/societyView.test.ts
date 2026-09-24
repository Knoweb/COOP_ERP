import { describe, expect, it } from "vitest";
import { actionsFor } from "./SocietyCardPage";
import type { Society } from "./partyApi";
import { STATUSES, STATUS_LOOK, legalNameIn } from "./societyView";
import partyMessages from "./party.messages.json" with { type: "json" };

const society = (overrides: Partial<Society>): Society => ({
  entityId: "0190f000-0000-7000-8000-000000000002",
  entityCode: "M001",
  entityType: "MPCS",
  legalNameEn: "Development MPCS",
  legalNameSi: "සංවර්ධන සමිතිය",
  legalNameTa: null,
  status: "ONBOARDING",
  ...overrides
});

const wiring = (overrides: Partial<Parameters<typeof actionsFor>[1]> = {}) => ({
  canActivate: true,
  canSuspend: true,
  t: (id: string) => id,
  busy: false,
  onActivate: () => {},
  onSuspend: () => {},
  onReinstate: () => {},
  ...overrides
});

describe("the actions of a society card", () => {
  it("offers Activate to an onboarding society, disabled with the reason while a prerequisite is missing", () => {
    const [activate] = actionsFor(society({ responsibleOfficerUserId: null, vatRegistrationNo: "VAT-1" }), wiring());
    expect(activate.id).toBe("activate");
    expect(activate.disabledReason).toBe("party.card.activate.no_officer");

    const [noVat] = actionsFor(society({ responsibleOfficerUserId: "u1", vatRegistrationNo: null }), wiring());
    expect(noVat.disabledReason).toBe("party.card.activate.no_vat");

    const [ready] = actionsFor(society({ responsibleOfficerUserId: "u1", vatRegistrationNo: "VAT-1" }), wiring());
    expect(ready.disabledReason).toBeUndefined();
    expect(ready.primary).toBe(true);
  });

  it("offers Suspend to an active society and Reinstate to a suspended one, and nothing else", () => {
    expect(actionsFor(society({ status: "ACTIVE" }), wiring()).map((a) => a.id)).toEqual(["suspend"]);
    expect(actionsFor(society({ status: "SUSPENDED" }), wiring()).map((a) => a.id)).toEqual(["reinstate"]);
  });

  it("offers nothing the user has no permission for: the server would refuse it anyway", () => {
    expect(actionsFor(society({ status: "ONBOARDING" }), wiring({ canActivate: false }))).toEqual([]);
    expect(actionsFor(society({ status: "ACTIVE" }), wiring({ canSuspend: false }))).toEqual([]);
    expect(actionsFor(society({ status: "SUSPENDED" }), wiring({ canSuspend: false }))).toEqual([]);
  });

  it("disables every action while a command is under way", () => {
    expect(actionsFor(society({ status: "ACTIVE" }), wiring({ busy: true }))[0].pending).toBe(true);
  });
});

describe("how a society is shown", () => {
  it("has a chip look and a word in three languages for every status", () => {
    for (const status of STATUSES) {
      expect(STATUS_LOOK[status]).toBeTruthy();
      for (const locale of ["en", "si", "ta"] as const) {
        expect((partyMessages[locale] as Record<string, string>)[`party.status.${status}`], `${status} in ${locale}`).toBeTruthy();
      }
    }
  });

  it("gives the legal name in the user's language and null where it is not translated", () => {
    const shown = society({});
    expect(legalNameIn(shown, "si")).toBe("සංවර්ධන සමිතිය");
    expect(legalNameIn(shown, "ta")).toBeNull();
    expect(legalNameIn(shown, "en")).toBe("Development MPCS");
  });

  it("has words for every bulk problem code the server can send", () => {
    for (const code of ["value_required", "value_invalid", "value_too_long", "duplicate_in_file", "code_exists"]) {
      expect((partyMessages.en as Record<string, string>)[`party.bulk.problem.bulk.row.${code}`], code).toBeTruthy();
    }
  });
});
