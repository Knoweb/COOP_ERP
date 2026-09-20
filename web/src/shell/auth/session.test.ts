import { describe, expect, it } from "vitest";
import { sessionFromAccessToken } from "./session";

/** A token with these claims. Unsigned: the client reads claims, the server verifies them. */
function token(claims: Record<string, unknown>): string {
  const part = (value: unknown) =>
    btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(value))))
      .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${part({ alg: "none" })}.${part(claims)}.`;
}

describe("the session read from an access token", () => {
  it("carries who the user is, the home entity, the policy class, the language and the roles", () => {
    const session = sessionFromAccessToken(token({
      sub: "u-1", name: "සුනිල් පෙරේරා", preferred_username: "mpcs-admin",
      ent: "0190f000-0000-7000-8000-000000000002", cls: "OWN", lang: "si", roles: ["mpcs-admin"]
    }));

    expect(session).toEqual({
      userId: "u-1",
      displayName: "සුනිල් පෙරේරා",          // decoded as UTF-8, not as Latin-1
      entityId: "0190f000-0000-7000-8000-000000000002",
      policyClass: "OWN",
      language: "si",
      roles: ["mpcs-admin"]
    });
  });

  it("uses the login name when the token has no full name", () => {
    expect(sessionFromAccessToken(token({ sub: "u", preferred_username: "cashier" })).displayName).toBe("cashier");
  });

  it("falls to the safe side for anything missing or unknown", () => {
    const session = sessionFromAccessToken(token({ sub: "u", cls: "SUPERUSER", lang: "fr", roles: "admin" }));

    expect(session.entityId).toBeNull();
    expect(session.policyClass).toBe("NONE");   // the server then shows nothing
    expect(session.language).toBeNull();        // the shell then uses English
    expect(session.roles).toEqual([]);
  });

  it("refuses something that is not a token", () => {
    expect(() => sessionFromAccessToken("not-a-jwt")).toThrow();
  });
});
