import { useState, useRef, useEffect } from "react";
import { useT } from "../i18n/useT";
import { useSession } from "./session";
import { useScope } from "../scope/useScope";

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
    <div className="user-menu" ref={menuRef} style={{ display: 'flex', alignItems: 'center', gap: '24px', position: 'relative' }}>
      <button type="button" className="btn-icon" aria-label="Notifications" style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--color-text-muted)' }}>
        <svg viewBox="0 0 24 24" width="22" height="22" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
          <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path>
          <path d="M13.73 21a2 2 0 0 1-3.46 0"></path>
        </svg>
      </button>

      <div
        className="user-menu__profile"
        style={{ display: 'flex', alignItems: 'center', gap: '12px', cursor: 'pointer' }}
        onClick={() => setIsOpen(!isOpen)}
      >
        <div style={{ position: 'relative', display: 'flex' }}>
          <div className="user-menu__avatar" aria-hidden="true" style={{ width: '36px', height: '36px', borderRadius: '50%', background: 'url("https://ui-avatars.com/api/?name=' + session.displayName.replace(' ', '+') + '&background=F6D7E9&color=8E0E5B") center/cover' }}></div>
          <div style={{ position: 'absolute', bottom: '-2px', right: '-2px', width: '12px', height: '12px', backgroundColor: '#00A651', border: '2px solid #F3E6F6', borderRadius: '50%' }}></div>
        </div>
        <div className="user-menu__info" style={{ display: 'flex', flexDirection: 'column' }}>
          <span style={{ fontSize: '12px', color: 'var(--color-text-muted)', lineHeight: '1.2' }}>User</span>
          <span className="user-menu__name" style={{ fontSize: '14px', fontWeight: '600', color: 'var(--color-text)', lineHeight: '1.2' }}>{session.displayName}</span>
        </div>
        <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--color-text-muted)', marginLeft: '4px' }}>
          <polyline points="6 9 12 15 18 9"></polyline>
        </svg>
      </div>

      {isOpen && (
        <div style={{
          position: 'absolute',
          top: '100%',
          right: 0,
          marginTop: '16px',
          background: 'var(--color-surface)',
          borderRadius: '16px',
          boxShadow: '0 10px 40px rgba(142, 14, 91, 0.1)',
          padding: '20px',
          minWidth: '260px',
          zIndex: 100,
          border: '1px solid rgba(142, 14, 91, 0.05)'
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', borderBottom: '1px solid var(--color-border)', paddingBottom: '16px', marginBottom: '16px' }}>
            <div style={{ width: '40px', height: '40px', borderRadius: '50%', background: 'url("https://ui-avatars.com/api/?name=' + session.displayName.replace(' ', '+') + '&background=F6D7E9&color=8E0E5B") center/cover' }}></div>
            <div style={{ display: 'flex', flexDirection: 'column' }}>
              <strong style={{ fontSize: '15px', color: 'var(--color-text)' }}>{session.displayName}</strong>
              <span style={{ fontSize: '12px', color: 'var(--color-text-muted)' }}>{t(`shell.scope.class.${scope.policyClass}`).text}</span>
            </div>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '10px 12px', background: 'rgba(246, 215, 233, 0.3)', borderRadius: '8px', color: 'var(--color-text-muted)', fontSize: '13px' }}>
              <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"></path>
                <circle cx="12" cy="10" r="3"></circle>
              </svg>
              {location}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '10px 12px', background: 'rgba(246, 215, 233, 0.3)', borderRadius: '8px', color: 'var(--color-text)', fontSize: '13px' }}>
              <svg viewBox="0 0 24 24" width="16" height="16" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={{ color: 'var(--color-accent)' }}>
                <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path>
              </svg>
              <strong style={{ fontWeight: 600 }}>{t("shell.scope.access").text}</strong>
              <span>{t(`shell.scope.class.${scope.policyClass}`).text}</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
