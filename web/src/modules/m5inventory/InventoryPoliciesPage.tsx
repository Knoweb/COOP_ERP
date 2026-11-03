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
      <Link className="back-link" to="/inventory">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("inventory.back").text}
      </Link>
      <PageHeader icon="stock" title={t("inventory.policy.title").text} />
      <p className="modern-intro-text">{t("inventory.policy.intro").text}</p>
      <p className="inventory-policy-notice" role="status">{t("inventory.policy.read_unavailable").text}</p>
      <section className="inventory-policy-grid">
        {POLICY_AREAS.map((area) => <article className="modern-table-card inventory-policy-card" key={area}><h2>{t(`inventory.policy.${area}.title`).text}</h2><p>{t(`inventory.policy.${area}.description`).text}</p></article>)}
      </section>
    </main>
  );
}
