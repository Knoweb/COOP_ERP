import { useContext, useEffect } from "react";
import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ScopeContext } from "../../shell/scope/ScopeContext";
import "./inventory.css";
import { inLocale } from "../../shell/i18n/localName";
import { errorText } from "./stockView";
import { useInventoryApi, type Location } from "./inventoryApi";
import { CustomSelect } from "./CustomSelect";

function nameOf(location: Location, locale: string): string {
  return inLocale(locale, location.nameEn, location.nameSi, location.nameTa);
}

/** The locations the caller's scope sees (M1's list); the first is chosen until the user picks. */
export function LocationPicker({ value, onChange }: { value: string; onChange: (locationId: string) => void }) {
  const t = useT();
  const intl = useIntl();
  const api = useInventoryApi();
  const locations = useQuery({ queryKey: ["inventory", "locations"], queryFn: () => api.locations(), staleTime: Infinity });
  // Read without useScope(), which throws outside the shell's layout: the picker is also
  // rendered alone in tests.
  const scopeLocationId = useContext(ScopeContext)?.active.locationId ?? null;

  useEffect(() => {
    // Preselects a location, so that a page opens with something on it (demo walkthrough,
    // 29 September 2026: Counts and Write-offs stayed empty while the select already showed a
    // shop): the location of the caller's scope when the list holds it, else the first one.
    const list = locations.data ?? [];
    if (!value && list.length > 0) {
      const scoped = list.find((location) => location.locationId === scopeLocationId);
      onChange((scoped ?? list[0]).locationId);
    }
  }, [value, locations.data, onChange, scopeLocationId]);

  if (locations.isError) {
    // A 403 (permission.denied prt.location.view) or any other failure: an empty select would
    // read as "you have no location", which is not what happened (bug seen live 28 Sep 2026).
    return <p role="alert">{errorText(locations.error, t("inventory.location.list_refused").text)}</p>;
  }

  const options = (locations.data ?? []).map((location) => ({
    value: location.locationId,
    label: `${location.locationCode} ${nameOf(location, intl.locale)}`,
  }));

  return (
    <label className="inventory-form-field">
      <span className="inventory-form-field__label">{t("inventory.field.location").text}</span>
      <CustomSelect
        value={value}
        onChange={onChange}
        ariaLabel={t("inventory.field.location").text}
        options={options}
      />
    </label>
  );
}
