import createClient from "openapi-fetch";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { paths } from "../../generated/hello";
import type { Session } from "../auth/session";
import { ApiProblem, apiMiddleware } from "./client";

const session: Session = {
  userId: "0190f000-0000-7000-8000-0000000000aa",
  displayName: "cashier",
  entityId: "0190f000-0000-7000-8000-000000000002",
  policyClass: "OWN",
  language: "ta",
  roles: ["cashier"]
};

/** A client whose network is this function; returns what was sent. */
function clientAnswering(answer: () => Response) {
  const sent: Request[] = [];
  const fetch = vi.fn(async (request: Request) => {
    sent.push(request);
    return answer();
  });
  const client = createClient<paths>({ baseUrl: "http://api.test", fetch });
  client.use(apiMiddleware(() => ({ accessToken: "the-token", locale: "ta", session })));
  return { client, sent };
}

const json = (status: number, body: unknown, type = "application/json") =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": type } });

describe("the API client", () => {
  afterEach(() => vi.restoreAllMocks());

  it("sends the token, the language and the scope of the signed-in user with every request", async () => {
    const { client, sent } = clientAnswering(() => json(200, []));

    await client.GET("/v1/hello/greetings");

    const headers = sent[0].headers;
    expect(headers.get("Authorization")).toBe("Bearer the-token");
    expect(headers.get("Accept-Language")).toBe("ta");
    // The active scope: the entity the user acts in now (the server checks it against the token).
    expect(headers.get("X-Scope-Entity")).toBe(session.entityId);
    expect(headers.get("X-Dev-User")).toBeNull();
  });

  it("sends the user for a fresh second factor when the server asks for one, and still reports the problem", async () => {
    const stepUp = vi.fn();
    const sent: Request[] = [];
    const fetch = vi.fn(async (request: Request) => {
      sent.push(request);
      return json(401, { status: 401, code: "mfa.required", title: "Fresh second factor needed", params: { stepUpUrl: "http://idp/auth" } }, "application/problem+json");
    });
    const client = createClient<paths>({ baseUrl: "http://api.test", fetch });
    client.use(apiMiddleware(() => ({ accessToken: "the-token", locale: "ta", session, stepUp })));

    const error = await client.GET("/v1/hello/greetings").catch((e: unknown) => e);

    expect(stepUp).toHaveBeenCalledTimes(1);
    expect((error as ApiProblem).problem.code).toBe("mfa.required");
  });

  it("refuses to send a mutating request without an Idempotency-Key", async () => {
    const { client, sent } = clientAnswering(() => json(201, {}));

    await expect(
      // @ts-expect-error the generated types demand the header; this is the JavaScript caller who ignores them
      client.POST("/v1/hello/greetings", { body: { textEn: "Hello" } })
    ).rejects.toThrow(/no Idempotency-Key/);
    expect(sent).toHaveLength(0);
  });

  it("sends a mutating request that carries its key, with that key", async () => {
    const { client, sent } = clientAnswering(() => json(201, { id: "x" }));

    await client.POST("/v1/hello/greetings", {
      params: { header: { "Idempotency-Key": "key-1" } },
      body: { textEn: "Hello" }
    });

    expect(sent[0].headers.get("Idempotency-Key")).toBe("key-1");
  });

  it("turns a problem document into an ApiProblem with its code and translated title", async () => {
    const { client } = clientAnswering(() =>
      json(422, { status: 422, code: "hello.greeting.duplicate", title: "இந்த வாழ்த்து ஏற்கனவே பதிவு செய்யப்பட்டுள்ளது" }, "application/problem+json"));

    const error = await client.GET("/v1/hello/greetings").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiProblem);
    expect((error as ApiProblem).problem.code).toBe("hello.greeting.duplicate");
    expect((error as ApiProblem).problem.title).toContain("வாழ்த்து");
    expect((error as ApiProblem).fieldErrors).toEqual({});
  });

  it("gives a form the message of every field the server refused", async () => {
    const { client } = clientAnswering(() =>
      json(400, {
        status: 400, code: "request.invalid", title: "Some of the information sent is missing or not valid",
        errors: [
          { field: "textEn", code: "request.field.required", message: "This is required", params: {} },
          { field: "textTa", code: "request.field.too_long", message: "Too long: the maximum is 200", params: { max: 200 } }
        ]
      }, "application/problem+json"));

    const error = (await client.GET("/v1/hello/greetings").catch((e: unknown) => e)) as ApiProblem;

    expect(error.fieldErrors).toEqual({ textEn: "This is required", textTa: "Too long: the maximum is 200" });
  });

  it("reports an error that is not from our API as an unknown problem, with its status", async () => {
    const { client } = clientAnswering(() => new Response("<html>Bad gateway</html>", { status: 502 }));

    const error = (await client.GET("/v1/hello/greetings").catch((e: unknown) => e)) as ApiProblem;

    expect(error.problem).toEqual({ status: 502, code: "unknown" });
  });
});
