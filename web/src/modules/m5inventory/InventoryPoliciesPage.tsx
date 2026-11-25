import { Link } from "react-router-dom";
import { useT } from "../../shell/i18n/useT";
import { PageHeader } from "../../shell/components/PageHeader";
import "./inventory.css";

const POLICY_AREAS = ["count", "approval", "writeoff", "expiry", "dispatch", "repack", "transfer"] as const;

/**
 * The policy catalogue. Effective values are deliberately not guessed here: the current M5
 * contract has no policy read/write operation, although 25A section 5 specifies one. The screen
 * therefore explains which controls are enforced and makes the missing management contract
 * explicit instead of showing seed defaults that may have entity overrides.
 */
export function InventoryPoliciesPage() {
  const t = useT();
  return (
    <main className="shell-page">
      <div>
        <Link className="back-pill-btn" to="/inventory">
          <svg
            viewBox="0 0 24 24"
            width="16"
            height="16"
            stroke="currentColor"
            strokeWidth="2"
            fill="none"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="m12 19-7-7 7-7" />
            <path d="M19 12H5" />
          </svg>
          {t("inventory.back").text}
        </Link>
      </div>
      <PageHeader icon="rules" title={t("inventory.policy.title").text} />
      <p className="modern-intro-text">{t("inventory.policy.intro").text}</p>
      <p className="inventory-policy-notice" role="status">{t("inventory.policy.read_unavailable").text}</p>
      <section className="inventory-policy-grid">
        {POLICY_AREAS.map((area) => <article className="modern-table-card inventory-policy-card" key={area}><h2>{t(`inventory.policy.${area}.title`).text}</h2><p>{t(`inventory.policy.${area}.description`).text}</p></article>)}
      </section>
    </main>
  );
}
