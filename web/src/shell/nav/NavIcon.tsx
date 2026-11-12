import type { SVGProps } from "react";

const ICON_PROPS: SVGProps<SVGSVGElement> = {
  viewBox: "0 0 24 24",
  width: 18,
  height: 18,
  stroke: "currentColor",
  strokeWidth: 2,
  fill: "none",
  strokeLinecap: "round",
  strokeLinejoin: "round",
  "aria-hidden": true,
  focusable: false
};

function normalizePath(to: string): string {
  return to.length > 1 && to.endsWith("/") ? to.slice(0, -1) : to;
}

/**
 * Distinct, uniform stroke icon for every back-office navigation route.
 * Exact routes are matched before module prefixes so sub-pages never inherit a parent icon by accident.
 */
export function NavIcon({ to }: { to: string }) {
  const path = normalizePath(to);

  if (path === "/hello") {
    return (
      <svg {...ICON_PROPS}>
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        <path d="M8 10h.01" />
        <path d="M12 10h.01" />
        <path d="M16 10h.01" />
      </svg>
    );
  }

  if (path.startsWith("/party/societies")) {
    return (
      <svg {...ICON_PROPS}>
        <line x1="3" y1="21" x2="21" y2="21" />
        <line x1="6" y1="18" x2="6" y2="10" />
        <line x1="10" y1="18" x2="10" y2="10" />
        <line x1="14" y1="18" x2="14" y2="10" />
        <line x1="18" y1="18" x2="18" y2="10" />
        <polygon points="12 3 3 8 21 8" />
      </svg>
    );
  }

  if (path.startsWith("/party/users")) {
    return (
      <svg {...ICON_PROPS}>
        <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
        <circle cx="12" cy="7" r="4" />
      </svg>
    );
  }

  if (path.startsWith("/party/roles")) {
    return (
      <svg {...ICON_PROPS}>
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
        <path d="m9 12 2 2 4-4" />
      </svg>
    );
  }

  if (path.startsWith("/party/grants")) {
    return (
      <svg {...ICON_PROPS}>
        <circle cx="7.5" cy="15.5" r="4.5" />
        <path d="m21 2-9.6 9.6" />
        <path d="m15.5 7.5 3 3L22 7l-3-3" />
      </svg>
    );
  }

  if (path.startsWith("/party/relationships")) {
    return (
      <svg {...ICON_PROPS}>
        <circle cx="18" cy="5" r="3" />
        <circle cx="6" cy="12" r="3" />
        <circle cx="18" cy="19" r="3" />
        <line x1="8.59" y1="13.51" x2="15.42" y2="17.49" />
        <line x1="15.41" y1="6.51" x2="8.59" y2="10.49" />
      </svg>
    );
  }

  if (path.startsWith("/party")) {
    return (
      <svg {...ICON_PROPS}>
        <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
        <circle cx="9" cy="7" r="4" />
        <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
        <path d="M16 3.13a4 4 0 0 1 0 7.75" />
      </svg>
    );
  }

  if (path === "/catalogue/departments") {
    return (
      <svg {...ICON_PROPS}>
        <rect x="9" y="3" width="6" height="4" rx="1" />
        <rect x="3" y="17" width="6" height="4" rx="1" />
        <rect x="15" y="17" width="6" height="4" rx="1" />
        <path d="M12 7v5" />
        <path d="M6 17v-3a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v3" />
      </svg>
    );
  }

  if (path === "/catalogue/uom") {
    return (
      <svg {...ICON_PROPS}>
        <path d="M20.7 6.3 17.7 3.3a1 1 0 0 0-1.4 0l-13 13a1 1 0 0 0 0 1.4l3 3a1 1 0 0 0 1.4 0l13-13a1 1 0 0 0 0-1.4Z" />
        <path d="m14 6 2 2" />
        <path d="m11 9 2 2" />
        <path d="m8 12 2 2" />
        <path d="m5 15 2 2" />
      </svg>
    );
  }

  if (path === "/catalogue/tax-rates") {
    return (
      <svg {...ICON_PROPS}>
        <line x1="19" y1="5" x2="5" y2="19" />
        <circle cx="6.5" cy="6.5" r="2.5" />
        <circle cx="17.5" cy="17.5" r="2.5" />
      </svg>
    );
  }

  if (path === "/catalogue/suppliers") {
    return (
      <svg {...ICON_PROPS}>
        <path d="M14 18V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v11a1 1 0 0 0 1 1h2" />
        <path d="M15 18H9" />
        <path d="M19 18h2a1 1 0 0 0 1-1v-3.65a1 1 0 0 0-.22-.624l-3.48-4.35A1 1 0 0 0 17.52 8H14" />
        <circle cx="17" cy="18" r="2" />
        <circle cx="7" cy="18" r="2" />
      </svg>
    );
  }

  if (path === "/catalogue/suspects") {
    return (
      <svg {...ICON_PROPS}>
        <rect x="8" y="8" width="13" height="13" rx="2" />
        <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
        <path d="M14.5 12v3" />
        <path d="M14.5 17.5h.01" />
      </svg>
    );
  }

  if (path === "/catalogue/import") {
    return (
      <svg {...ICON_PROPS}>
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
        <polyline points="17 8 12 3 7 8" />
        <line x1="12" y1="3" x2="12" y2="15" />
      </svg>
    );
  }

  if (path.startsWith("/catalogue")) {
    return (
      <svg {...ICON_PROPS}>
        <rect x="3" y="3" width="7" height="7" rx="1.5" />
        <rect x="14" y="3" width="7" height="7" rx="1.5" />
        <rect x="14" y="14" width="7" height="7" rx="1.5" />
        <rect x="3" y="14" width="7" height="7" rx="1.5" />
      </svg>
    );
  }

  if (path.startsWith("/pricing")) {
    return (
      <svg {...ICON_PROPS}>
        <path d="M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z" />
        <circle cx="7.5" cy="7.5" r="1.25" />
      </svg>
    );
  }

  if (path.startsWith("/trading")) {
    return (
      <svg {...ICON_PROPS}>
        <circle cx="9" cy="21" r="1" />
        <circle cx="20" cy="21" r="1" />
        <path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6" />
      </svg>
    );
  }

  if (path.startsWith("/inventory")) {
    return (
      <svg {...ICON_PROPS}>
        <line x1="16.5" y1="9.4" x2="7.5" y2="4.21" />
        <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
        <polyline points="3.27 6.96 12 12.01 20.73 6.96" />
        <line x1="12" y1="22.08" x2="12" y2="12" />
      </svg>
    );
  }

  if (path.startsWith("/pos")) {
    return (
      <svg {...ICON_PROPS}>
        <path d="M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1Z" />
        <path d="M8 7h8" />
        <path d="M8 11h8" />
        <path d="M8 15h5" />
      </svg>
    );
  }

  if (path.startsWith("/customers")) {
    return (
      <svg {...ICON_PROPS}>
        <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
        <circle cx="9" cy="7" r="4" />
        <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
        <path d="M16 3.13a4 4 0 0 1 0 7.75" />
      </svg>
    );
  }

  if (path === "/reporting/shop-reports") {
    return (
      <svg {...ICON_PROPS}>
        <path d="m2 7 4.41-4.41A2 2 0 0 1 7.83 2h8.34a2 2 0 0 1 1.42.59L22 7" />
        <path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8" />
        <path d="M15 22v-4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4" />
        <path d="M2 7h20" />
      </svg>
    );
  }

  if (path === "/reporting/profit-centers") {
    return (
      <svg {...ICON_PROPS}>
        <path d="M21.21 15.89A10 10 0 1 1 8 2.83" />
        <path d="M22 12A10 10 0 0 0 12 2v10z" />
      </svg>
    );
  }

  if (path === "/reporting/assets") {
    return (
      <svg {...ICON_PROPS}>
        <rect x="8" y="2" width="8" height="4" rx="1" />
        <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
        <path d="M12 11h4" />
        <path d="M12 16h4" />
        <path d="M8 11h.01" />
        <path d="M8 16h.01" />
      </svg>
    );
  }

  if (path === "/reporting/targets") {
    return (
      <svg {...ICON_PROPS}>
        <circle cx="12" cy="12" r="10" />
        <circle cx="12" cy="12" r="6" />
        <circle cx="12" cy="12" r="2" />
      </svg>
    );
  }

  if (path === "/reporting/revenue-estimator") {
    return (
      <svg {...ICON_PROPS}>
        <polyline points="22 7 13.5 15.5 8.5 10.5 2 17" />
        <polyline points="16 7 22 7 22 13" />
      </svg>
    );
  }

  if (path === "/reporting/finance") {
    return (
      <svg {...ICON_PROPS}>
        <path d="M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1" />
        <path d="M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4" />
      </svg>
    );
  }

  if (path.startsWith("/reporting")) {
    return (
      <svg {...ICON_PROPS}>
        <path d="M3 3v18h18" />
        <path d="M18 17V9" />
        <path d="M13 17V5" />
        <path d="M8 17v-3" />
      </svg>
    );
  }

  if (path.startsWith("/integration/journal")) {
    return (
      <svg {...ICON_PROPS}>
        <path d="M4 22h14a2 2 0 0 0 2-2V7l-5-5H6a2 2 0 0 0-2 2v4" />
        <path d="M14 2v4a2 2 0 0 0 2 2h4" />
        <path d="M2 15h10" />
        <path d="m9 18 3-3-3-3" />
      </svg>
    );
  }

  if (path.startsWith("/integration/notifications")) {
    return (
      <svg {...ICON_PROPS}>
        <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
        <path d="M13.73 21a2 2 0 0 1-3.46 0" />
      </svg>
    );
  }

  return (
    <svg {...ICON_PROPS}>
      <circle cx="12" cy="12" r="9" />
      <line x1="12" y1="8" x2="12" y2="12" />
      <line x1="12" y1="16" x2="12.01" y2="16" />
    </svg>
  );
}

