import { NavLink } from "react-router-dom";
import { useT } from "../../shell/i18n/useT";

/**
 * The screens of the pricing module (23A section 8), one link each: the trade lists, the society's
 * shelf prices, the gazette of control prices and the MRP policy. The shell has one navigation
 * entry for the module; this row leads from it to each screen.
 */
export function PricingTabs() {
  const t = useT();
  const tabs = [
    { to: "/pricing", labelId: "pricing.tab.trade", end: true },
    { to: "/pricing/shelf", labelId: "pricing.tab.shelf", end: false },
    { to: "/pricing/gazette", labelId: "pricing.tab.gazette", end: false },
    { to: "/pricing/mrp-policy", labelId: "pricing.tab.mrp_policy", end: false }
  ];
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
