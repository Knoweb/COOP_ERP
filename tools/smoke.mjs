// Smoke test of the running local stack: `make smoke` after `make up`, or `make smoke TWO=1`
// after `make up-2`. The pipeline runs the second form on every push (17A section 11, e2e).
//
// It checks, from outside and through the published ports, that the pieces work together:
// the backend is healthy and migrated, the web client answers, the dev realm issues tokens
// with the scope claims, the hello module respects scope and idempotency through PgBouncer,
// errors are translated, and with TWO=1 that nginx really alternates between two instances.
//
// What it is not: the Playwright run of 17A (login, scope banner, hello screen in a browser).
// That needs the login flow of the web shell (S0-07) and replaces nothing here when it comes.
//
// Node standard library only (fetch is built in from Node 18).

const BACKEND = process.env.SMOKE_BACKEND ?? "http://localhost:8080";
const WEB = process.env.SMOKE_WEB ?? "http://localhost:5173";
const KEYCLOAK = process.env.SMOKE_KEYCLOAK ?? "http://localhost:8085";
const TWO_INSTANCES = process.argv.includes("--two");

// The development entities of infra/compose/realm-dev.json and the hello seed.
const FEDERATION = "0190f000-0000-7000-8000-000000000001";
const MPCS = "0190f000-0000-7000-8000-000000000002";
const GREETINGS = `${BACKEND}/v1/hello/greetings`;

let failures = 0;

async function check(name, body) {
  try {
    await body();
    console.log(`  ok    ${name}`);
  } catch (error) {
    failures++;
    console.log(`  FAIL  ${name}\n        ${error.message}`);
  }
}

function expect(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

const json = async (response) => {
  const text = await response.text();
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`expected JSON, got HTTP ${response.status}: ${text.slice(0, 200)}`);
  }
};

const scope = (entity, extra = {}) => ({ "X-Scope-Entity": entity, ...extra });

function register(textEn, key, headers) {
  return fetch(GREETINGS, {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": key, ...headers },
    body: JSON.stringify({ textEn })
  });
}

console.log(`Smoke test of ${BACKEND}${TWO_INSTANCES ? " (two instances behind nginx)" : ""}`);

await check("backend is healthy (so the database is migrated)", async () => {
  const health = await json(await fetch(`${BACKEND}/actuator/health`));
  expect(health.status === "UP", `health is ${JSON.stringify(health)}`);
});

await check("readiness probe and metrics answer (17A S0-02: health and metrics)", async () => {
  const readiness = await json(await fetch(`${BACKEND}/actuator/health/readiness`));
  expect(readiness.status === "UP", `readiness is ${JSON.stringify(readiness)}`);
  const metrics = await fetch(`${BACKEND}/actuator/prometheus`);
  expect(metrics.status === 200, `/actuator/prometheus answered HTTP ${metrics.status}`);
  expect((await metrics.text()).includes("jvm_memory_used_bytes"), "no JVM metrics in the Prometheus output");
});

await check("web client answers", async () => {
  const response = await fetch(`${WEB}/hello`);
  expect(response.status === 200, `HTTP ${response.status}`);
  expect((await response.text()).includes('id="root"'), "the page has no root element");
});

await check("dev realm issues a token with the scope claims, for each dev user", async () => {
  for (const [user, entity, language] of [["fed-admin", FEDERATION, "en"], ["mpcs-admin", MPCS, "si"], ["cashier", MPCS, "ta"]]) {
    const response = await fetch(`${KEYCLOAK}/realms/coop/protocol/openid-connect/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "password", client_id: "coop-erp-web", username: user, password: "dev", scope: "openid" })
    });
    const token = (await json(response)).access_token;
    expect(token, `${user}: no access token (HTTP ${response.status})`);
    const claims = JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString("utf8"));
    expect(claims.ent === entity, `${user}: claim ent is ${claims.ent}`);
    expect(claims.lang === language, `${user}: claim lang is ${claims.lang}`);
    expect(Array.isArray(claims.roles) && claims.roles.includes(user), `${user}: claim roles is ${JSON.stringify(claims.roles)}`);
  }
});

await check("an entity sees its own seeded greetings", async () => {
  const greetings = await json(await fetch(GREETINGS, { headers: scope(MPCS) }));
  const texts = greetings.map((g) => g.textEn);
  expect(texts.includes("Welcome from the society"), `seed row missing; got ${JSON.stringify(texts)}`);
  expect(!texts.includes("Welcome from the Federation"), "the MPCS can see the Federation's greeting");
});

await check("without a scope nothing is visible", async () => {
  const greetings = await json(await fetch(GREETINGS));
  expect(Array.isArray(greetings) && greetings.length === 0, `got ${greetings.length} rows`);
});

await check("the federation view sees every entity", async () => {
  const greetings = await json(await fetch(GREETINGS, { headers: scope(FEDERATION, { "X-Dev-Scope-Class": "FEDERATION_VIEW" }) }));
  const texts = greetings.map((g) => g.textEn);
  expect(texts.includes("Welcome from the society") && texts.includes("Welcome from the Federation"), `got ${JSON.stringify(texts)}`);
});

await check("a greeting can be registered, in UTC, and read back", async () => {
  const text = `Smoke ${crypto.randomUUID()}`;
  const response = await register(text, crypto.randomUUID(), scope(MPCS));
  const created = await json(response);
  expect(response.status === 201, `HTTP ${response.status}: ${JSON.stringify(created)}`);
  expect(/Z$/.test(created.createdAt), `createdAt is not UTC: ${created.createdAt}`);
  expect(Math.abs(Date.now() - Date.parse(created.createdAt)) < 10 * 60 * 1000, `createdAt is not now: ${created.createdAt}`);
  const again = await fetch(`${GREETINGS}/${created.id}`, { headers: scope(MPCS) });
  expect(again.status === 200, `read back gave HTTP ${again.status}`);
  const other = await fetch(`${GREETINGS}/${created.id}`, { headers: scope(FEDERATION) });
  expect(other.status === 404, `another entity got HTTP ${other.status}, expected 404`);
});

await check("a duplicate is refused, with the message in the caller's language", async () => {
  const text = `Smoke duplicate ${crypto.randomUUID()}`;
  await register(text, crypto.randomUUID(), scope(MPCS));
  const response = await register(text, crypto.randomUUID(), scope(MPCS, { "Accept-Language": "ta" }));
  const problem = await json(response);
  expect(response.status === 422 && problem.code === "hello.greeting.duplicate", `HTTP ${response.status}: ${JSON.stringify(problem)}`);
  expect(/[஀-௿]/.test(problem.title), `title is not Tamil: ${problem.title}`);
});

await check("a request without an Idempotency-Key is refused", async () => {
  const response = await fetch(GREETINGS, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...scope(MPCS) },
    body: JSON.stringify({ textEn: "no key" })
  });
  expect(response.status === 400 && (await json(response)).code === "idempotency.key_required", `HTTP ${response.status}`);
});

if (TWO_INSTANCES) {
  await check("nginx alternates between two backend instances", async () => {
    const upstreams = new Set();
    for (let i = 0; i < 8; i++) {
      const response = await fetch(`${BACKEND}/actuator/health`);
      upstreams.add(response.headers.get("x-upstream"));
    }
    upstreams.delete(null);
    expect(upstreams.size === 2, `saw ${upstreams.size} instance(s): ${[...upstreams].join(", ") || "no X-Upstream header (is this make up-2?)"}`);
  });
} else {
  // One instance: the in-memory idempotency store of the 17A stub holds. With two instances a
  // retry can land on the other one; that case belongs to 19A K-03 (kernel.idempotency_key).
  await check("a retry with the same Idempotency-Key returns the same greeting", async () => {
    const text = `Smoke retry ${crypto.randomUUID()}`;
    const key = crypto.randomUUID();
    const first = await json(await register(text, key, scope(MPCS)));
    const retry = await json(await register(text, key, scope(MPCS)));
    expect(first.id && first.id === retry.id, `first ${first.id}, retry ${retry.id ?? JSON.stringify(retry)}`);
  });
}

console.log(failures === 0 ? "Smoke test passed" : `Smoke test FAILED: ${failures} check(s)`);
process.exit(failures === 0 ? 0 : 1);
