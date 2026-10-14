import { NavLink } from "react-router-dom";
import { useT } from "../../shell/i18n/useT";
import { useHasPermission } from "../../shell/auth/permissions";

/**
 * The screens of the pricing module (23A section 8), one link each: the trade lists, the society's
 * shelf prices, the gazette of control prices, the MRP policy, discount rules, pricing reviews,
 * and the markdown queue. The shell has one navigation entry for the module; this row leads from
 * it to each screen.
 */
export function PricingTabs() {
  const t = useT();
  const canViewReviews = useHasPermission("prc.review.view");

  const tabs = [
    { to: "/pricing", labelId: "pricing.tab.trade", end: true, show: true },
    { to: "/pricing/shelf", labelId: "pricing.tab.shelf", end: false, show: true },
    { to: "/pricing/gazette", labelId: "pricing.tab.gazette", end: false, show: true },
    { to: "/pricing/mrp-policy", labelId: "pricing.tab.mrp_policy", end: false, show: true },
    { to: "/pricing/rules", labelId: "pricing.tab.rules", end: false, show: true },
    { to: "/pricing/reviews", labelId: "pricing.tab.reviews", end: false, show: canViewReviews },
    { to: "/pricing/markdown", labelId: "pricing.tab.markdown", end: false, show: true }
  ].filter((tab) => tab.show);

  return (
    <nav className="pricing-tabs" aria-label={t("pricing.tabs").text}>
      {tabs.map((tab) => (
        <NavLink
          key={tab.to}
          to={tab.to}
          end={tab.end}
          className={({ isActive }) => (isActive ? "pricing-tab pricing-tab--active" : "pricing-tab")}
        >
          {t(tab.labelId).text}
        </NavLink>
      ))}
    </nav>
  );
}
