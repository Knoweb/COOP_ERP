import type { ReactNode } from "react";

export type PageIconName =
  | "catalogue"
  | "stock"
  | "pricing"
  | "society"
  | "shelf"
  | "gazette"
  | "mrp-policy"
  | "mrp_policy"
  | "rules"
  | "markdown"
  | "queue";

export function PageHeader({
  icon,
  title,
  actions
}: {
  icon: PageIconName | ReactNode;
  title: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="page-heading">
      <div className="page-heading__icon" aria-hidden="true">
        {typeof icon === "string" ? <PageIcon name={icon as PageIconName} /> : icon}
      </div>

      <div className="page-heading__copy">
        <h1>{title}</h1>
      </div>

      {actions ? <div className="page-heading__actions">{actions}</div> : null}
    </header>
  );
}

function PageIcon({ name }: { name: PageIconName }) {
  if (name === "catalogue") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M3 6 12 2l9 4-9 4-9-4Z" />
        <path d="M3 6v11l9 5 9-5V6" />
        <path d="M12 10v12" />
      </svg>
    );
  }

  if (name === "stock") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M3 10 12 3l9 7" />
        <path d="M5 9v12h14V9" />
        <path d="M8 13h8" />
        <path d="M8 17h8" />
      </svg>
    );
  }

  if (name === "pricing") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M4 5h9l7 7-8 8-8-8V5Z" />
        <circle cx="9" cy="10" r="1.5" />
      </svg>
    );
  }

  if (name === "shelf") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <rect x="3" y="3" width="18" height="18" rx="2" />
        <path d="M3 9h18" />
        <path d="M3 15h18" />
      </svg>
    );
  }

  if (name === "gazette") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M4 22h16a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v16a2 2 0 0 1-2 2Zm0 0a2 2 0 0 1-2-2v-9c0-1.1.9-2 2-2h2" />
        <path d="M18 14h-8" />
        <path d="M15 18h-5" />
        <path d="M10 6h8v4h-8V6Z" />
      </svg>
    );
  }

  if (name === "mrp-policy" || name === "mrp_policy") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
        <polyline points="9 12 11 14 15 10" />
      </svg>
    );
  }

  if (name === "rules") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
        <rect x="8" y="2" width="8" height="4" rx="1" ry="1" />
        <polyline points="9 14 11 16 15 12" />
      </svg>
    );
  }

  if (name === "markdown") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <line x1="19" y1="5" x2="5" y2="19" />
        <circle cx="6.5" cy="6.5" r="2.5" />
        <circle cx="17.5" cy="17.5" r="2.5" />
      </svg>
    );
  }

  if (name === "queue") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <line x1="10" y1="6" x2="21" y2="6" />
        <line x1="10" y1="12" x2="21" y2="12" />
        <line x1="10" y1="18" x2="21" y2="18" />
        <path d="M4 6h1v4" />
        <path d="M4 10h2" />
        <path d="M6 18H4c0-1 2-2 2-3s-1-1.5-2-1" />
      </svg>
    );
  }

  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d="M4 21V8l8-5 8 5v13" />
      <path d="M8 21v-5h8v5" />
      <path d="M8 10h1" />
      <path d="M12 10h1" />
      <path d="M16 10h1" />
    </svg>
  );
}