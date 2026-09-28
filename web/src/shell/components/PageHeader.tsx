import type { ReactNode } from "react";

export type PageIconName = "catalogue" | "stock" | "pricing" | "society";

export function PageHeader({
  icon,
  title,
  actions
}: {
  icon: PageIconName;
  title: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="page-heading">
      <div className="page-heading__icon" aria-hidden="true">
        <PageIcon name={icon} />
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