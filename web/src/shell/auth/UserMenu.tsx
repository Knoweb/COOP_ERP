import { useState, useRef, useEffect } from "react";
import { useT } from "../i18n/useT";
import { useSession } from "./session";
import { useScope } from "../scope/useScope";
import { initialsOf } from "../i18n/initials";

/** The signed-in person's initials in a circle; made in the browser, no name leaves it. */
export function Avatar({ name, size, className }: { name: string; size: number; className?: string }) {
  return (
    <span
      className={className}
      aria-hidden="true"
      style={{
        width: `${size}px`,
        height: `${size}px`,
        borderRadius: "50%",
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
        flexShrink: 0,
        fontSize: `${Math.round(size * 0.4)}px`,
        fontWeight: 600,
        background: "var(--color-accent-light)",
        color: "var(--color-accent)"
      }}
    >
      {initialsOf(name)}
    </span>
  );
}

/** Who is signed in, and the way out. Part of the shell's header on every page. */
export function UserMenu() {
  const session = useSession();
  const scope = useScope();
  const t = useT();
  const [isOpen, setIsOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, []);

  if (!session) {
    return null;
  }

  const location = scope.locationId ?? t("shell.scope.location_all").text;

  return (
    <div className="user-menu" ref={menuRef}>
      <button type="button" className="btn-icon user-menu__bell" aria-label={t("shell.user.notifications").text}>
        <svg viewBox="0 0 24 24" width="22" height="22" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path>
          <path d="M13.73 21a2 2 0 0 1-3.46 0"></path>
        </svg>
      </button>

      <div className="user-menu__profile" onClick={() => setIsOpen(!isOpen)}>
        <div className="user-menu__avatar-wrap">
          <Avatar name={session.displayName} size={36} className="user-menu__avatar" />
          <div className="user-menu__status"></div>
        </div>
        <div className="user-menu__info">
          <span className="user-menu__role">{t("shell.user.label").text}</span>
          <span className="user-menu__name">{session.displayName}</span>
        </div>
        <svg className="user-menu__chevron" viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <polyline points="6 9 12 15 18 9"></polyline>
        </svg>
      </div>

      {isOpen && (
        <div className="user-menu__panel">
          <div className="user-menu__panel-head">
            <Avatar name={session.displayName} size={40} />
            <div className="user-menu__info">
              <strong>{session.displayName}</strong>
              <span>{t(`shell.scope.class.${scope.policyClass}`).text}</span>
            </div>
          </div>

          <div className="user-menu__panel-body">
            <div className="user-menu__chip">
              <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"></path>
                <circle cx="12" cy="10" r="3"></circle>
              </svg>
              {location}
            </div>
            <div className="user-menu__chip user-menu__chip--access">
              <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path>
              </svg>
              <strong>{t("shell.scope.access").text}</strong>
              <span>{t(`shell.scope.class.${scope.policyClass}`).text}</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
