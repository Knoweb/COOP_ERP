// Opening a file the server hands us a link to (a printed invoice, a report run).
//
// The tab is opened in the click itself, so a popup blocker lets it through, and is sent to the
// file once the link arrives. Two rules (TWK-17): the new tab gets no handle back to this page
// (`opener = null`; the `noopener` feature would make window.open return null, and then there is
// no tab to send on), and the link is followed only when it is http(s) and points at this app,
// the API, or the object store the server signs its links for. Anything else is refused and the
// tab is closed.

import { pageConfig, resolveConfig } from "../runtimeConfig";

/** The origins a server-supplied file link may point at. */
export function fileOrigins(): string[] {
  const origins = new Set<string>();
  const add = (value: string | undefined) => {
    try {
      if (value) origins.add(new URL(value).origin);
    } catch {
      // not a URL: not an origin
    }
  };
  const apiBase = resolveConfig().apiBase;
  if (typeof window !== "undefined") add(window.location.origin);
  add(apiBase);
  const fromEnv: unknown = import.meta.env.VITE_OBJECT_STORE_ORIGIN;
  add(pageConfig().objectStoreOrigin ?? (typeof fromEnv === "string" ? fromEnv : undefined));
  // The local compose stack signs links for its published MinIO port.
  if (/^https?:\/\/(localhost|127\.0\.0\.1)(:|\/|$)/.test(apiBase)) add("http://localhost:9000");
  return [...origins];
}

/** True when `url` is an http(s) link to one of `allowed` origins. */
export function isOpenableFileUrl(url: string, allowed: string[] = fileOrigins()): boolean {
  try {
    const parsed = new URL(url);
    return (parsed.protocol === "https:" || parsed.protocol === "http:") && allowed.includes(parsed.origin);
  } catch {
    return false;
  }
}

/**
 * Open the file whose link `getUrl` returns, in a new tab opened now. Rejects (and closes the
 * tab) when the link cannot be had or is refused.
 */
export async function openServerFile(getUrl: () => Promise<string | null | undefined>): Promise<void> {
  const tab = window.open("about:blank", "_blank");
  if (tab) tab.opener = null;
  try {
    const url = await getUrl();
    if (!url || !isOpenableFileUrl(url)) {
      throw new Error("file link refused");
    }
    if (tab) {
      tab.location.href = url;
    } else {
      window.location.assign(url);
    }
  } catch (error) {
    tab?.close();
    throw error;
  }
}
