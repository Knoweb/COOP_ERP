import { describe, expect, it } from "vitest";

// The rule for every module (doc 19 section 5.1, DR-6; shell/i18n/formats.ts): a calendar date is
// shown as dd/MM/yyyy through useFormatDate, an instant through useFormatInstant, and an amount of
// money through MoneyDisplay. The API sends a date as 2026-09-25 and an amount as "1290", so a
// field put on the screen as it came shows exactly what the rule forbids (the demo walkthrough of
// 29 September found both on six screens).
//
// Like moduleStyle.test.ts this is a plain text search, line by line: it looks for a JSX child or
// a message parameter that is a field whose NAME says it is a date or money, taken straight from
// the data ({row.effectiveFrom}, {lot.expiryDate ?? ""}, { date: list.applyFrom },
// {batch.printedMrp}). An attribute (value={form.effectiveFrom} on an input, key=..., dateTime=...)
// is not shown as text and is allowed. If it stops you for a field that really is not a date, rename
// the field or format it; that is cheaper than explaining an ISO date to a shop manager.

const DATE_FIELD = String.raw`\w*(?:Date|From|Until|On)|expiry\w*|effectiveTo|validTo|dueDate`;
const MONEY_FIELD = String.raw`printedMrp|\w*Price|\w*Amount|unitCost|creditLimit`;
const RAW_CHILD = (field: string) => new RegExp(String.raw`(?<![=\w$])\{\s*[\w?.]+\.(?:${field})\s*(?:\?\?\s*"[^"]*"\s*)?\}`);
const RAW_PARAM = new RegExp(String.raw`\b(?:date|from|until|due)\s*:\s*[\w?.]+\.(?:${DATE_FIELD})\s*[,}]`);

/** The lines of a source text that show a date or money field raw: ["187: <td>{row.effectiveFrom}</td>"]. */
function rawValuesIn(source: string): string[] {
  return source
    .split(/\r?\n/)
    .map((line, index) => ({ line: line.trim(), number: index + 1 }))
    .filter(({ line }) => !/^(\/\/|\/\*|\*|\{\/\*)/.test(line))
    .filter(({ line }) => RAW_CHILD(DATE_FIELD).test(line) || RAW_CHILD(MONEY_FIELD).test(line) || RAW_PARAM.test(line))
    .map(({ line, number }) => `${number}: ${line}`);
}

describe("the search for raw dates and money", () => {
  it("finds a date field, an expiry, a message parameter and an amount shown as they came", () => {
    expect(rawValuesIn(`<td>{row.effectiveFrom}</td>`)).toHaveLength(1);
    expect(rawValuesIn(`<td>{lot.expiryDate ?? ""}</td>`)).toHaveLength(1);
    expect(rawValuesIn(`<td>{invoice.dueDate}</td>`)).toHaveLength(1);
    expect(rawValuesIn(`t("pricing.applies_from", undefined, { date: list.applyFrom }).text`)).toHaveLength(1);
    expect(rawValuesIn(`<td>{batch.printedMrp ?? ""}</td>`)).toHaveLength(1);
  });

  it("accepts formatted values, attributes and comments", () => {
    expect(rawValuesIn(`<td>{formatDate(row.effectiveFrom)}</td>`)).toEqual([]);
    expect(rawValuesIn(`<td>{lot.expiryDate ? formatDate(lot.expiryDate) : ""}</td>`)).toEqual([]);
    expect(rawValuesIn(`<input type="date" value={form.effectiveFrom} />`)).toEqual([]);
    expect(rawValuesIn(`<tr key={row.validFrom}>`)).toEqual([]);
    expect(rawValuesIn(`<MoneyDisplay amount={batch.printedMrp} />`)).toEqual([]);
    expect(rawValuesIn(`{ date: formatDate(list.applyFrom) }`)).toEqual([]);
    expect(rawValuesIn(`// the old cell was <td>{row.effectiveFrom}</td>`)).toEqual([]);
  });
});

describe("the web modules", () => {
  const sources = import.meta.glob(["../*.{ts,tsx}", "../modules/**/*.{ts,tsx}", "../shell/**/*.{ts,tsx}", "!../*.test.{ts,tsx}", "!../modules/**/*.test.{ts,tsx}", "!../shell/**/*.test.{ts,tsx}"], {
    query: "?raw",
    import: "default",
    eager: true
  }) as Record<string, string>;

  it("are found by this test (a wrong path would make it pass for ever)", () => {
    expect(Object.keys(sources)).toContain("../modules/m2catalogue/SkuPage.tsx");
  });

  it("show no date and no amount as the API sent it", () => {
    const found = Object.entries(sources).flatMap(([file, source]) =>
      rawValuesIn(source).map((line) => `${file.replace("../", "web/src/")}:${line}`)
    );
    expect(found, "a date goes through useFormatDate (dd/MM/yyyy), an amount through MoneyDisplay").toEqual([]);
  });

  it("take today's date from businessToday(), never from the browser's calendar (Asia/Colombo, TWK-12)", () => {
    const found = Object.entries(sources)
      .filter(([, source]) => browserLocalDayIn(source))
      .map(([file]) => file.replace("../", "web/src/"));
    expect(found, "use businessToday() of shell/i18n/formats.ts; the browser's zone is not the shop's").toEqual([]);
  });
});

// "Today" read from the clock of the browser and cut into a calendar day with the browser's own
// zone: new Date() together with getFullYear / getMonth / getDate / toISOString().slice. A date
// built from a calendar day in UTC (new Date(`${day}T00:00:00Z`)) is fine and has no empty
// new Date().
const CLOCK_NOW = /new Date\(\)|Date\.now\(\)/;
const LOCAL_DAY_PARTS = /\.(?:getFullYear|getMonth|getDate)\(\)|toISOString\(\)\.slice|\.toLocaleDateString\(|\.toDateString\(\)/;

function browserLocalDayIn(source: string): boolean {
  const code = source
    .split(/\r?\n/)
    .filter((line) => !/^\s*(\/\/|\/\*|\*)/.test(line))
    .join("\n");
  return CLOCK_NOW.test(code) && LOCAL_DAY_PARTS.test(code);
}

describe("the search for the browser's calendar day", () => {
  it("finds the clock cut into a local day", () => {
    expect(browserLocalDayIn(`const d = new Date(); return d.getFullYear() + "-" + d.getMonth();`)).toBe(true);
    expect(browserLocalDayIn(`return new Date().toISOString().slice(0, 10);`)).toBe(true);
    expect(browserLocalDayIn(`return new Date().toLocaleDateString("en-CA");`)).toBe(true);
    expect(browserLocalDayIn(`return new Date().toDateString();`)).toBe(true);
  });

  it("accepts a clock default with no day arithmetic, a UTC calendar day, and comments", () => {
    expect(browserLocalDayIn(`export function f(now: Date = new Date()) { return format(now); }`)).toBe(false);
    expect(browserLocalDayIn(`const d = new Date(\`\${day}T00:00:00Z\`); return d.toISOString().slice(0, 10);`)).toBe(false);
    expect(browserLocalDayIn(`// new Date().getFullYear() was here`)).toBe(false);
  });
});
