import { afterEach, describe, expect, it, vi } from "vitest";
import { isOpenableFileUrl, openServerFile } from "./openServerFile";

const allowed = ["https://app.example", "http://localhost:9000"];

describe("isOpenableFileUrl", () => {
  it("accepts http(s) links to an allowed origin", () => {
    expect(isOpenableFileUrl("https://app.example/files/a.pdf?sig=1", allowed)).toBe(true);
    expect(isOpenableFileUrl("http://localhost:9000/b/a.pdf", allowed)).toBe(true);
  });

  it("refuses another origin, another scheme and rubbish", () => {
    expect(isOpenableFileUrl("https://evil.example/a.pdf", allowed)).toBe(false);
    expect(isOpenableFileUrl("https://app.example.evil.example/a.pdf", allowed)).toBe(false);
    expect(isOpenableFileUrl("javascript:alert(1)", allowed)).toBe(false);
    expect(isOpenableFileUrl("data:text/html,x", allowed)).toBe(false);
    expect(isOpenableFileUrl("not a url", allowed)).toBe(false);
  });
});

describe("openServerFile", () => {
  afterEach(() => vi.restoreAllMocks());

  it("clears the opener, then sends the tab to an allowed link", async () => {
    const tab = { opener: "page", location: { href: "" }, close: vi.fn() };
    vi.spyOn(window, "open").mockReturnValue(tab as unknown as Window);
    await openServerFile(() => Promise.resolve(`${window.location.origin}/file.pdf`));
    expect(tab.opener).toBeNull();
    expect(tab.location.href).toBe(`${window.location.origin}/file.pdf`);
    expect(tab.close).not.toHaveBeenCalled();
  });

  it("closes the tab and rejects when the link points elsewhere", async () => {
    const tab = { opener: "page", location: { href: "" }, close: vi.fn() };
    vi.spyOn(window, "open").mockReturnValue(tab as unknown as Window);
    await expect(openServerFile(() => Promise.resolve("https://evil.example/a.pdf"))).rejects.toThrow();
    expect(tab.location.href).toBe("");
    expect(tab.close).toHaveBeenCalled();
  });

  it("closes the tab when the link cannot be had", async () => {
    const tab = { opener: "page", location: { href: "" }, close: vi.fn() };
    vi.spyOn(window, "open").mockReturnValue(tab as unknown as Window);
    await expect(openServerFile(() => Promise.reject(new Error("down")))).rejects.toThrow("down");
    expect(tab.close).toHaveBeenCalled();
  });
});
