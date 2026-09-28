import { useEffect } from "react";
import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import "./inventory.css";
import { inLocale } from "../../shell/i18n/localName";
import { errorText } from "./stockView";
import { useInventoryApi, type Location } from "./inventoryApi";

function nameOf(location: Location, locale: string): string {
  return inLocale(locale, location.nameEn, location.nameSi, location.nameTa);
}

/** The locations the caller's scope sees (M1's list); the first is chosen until the user picks. */
export function LocationPicker({ value, onChange }: { value: string; onChange: (locationId: string) => void }) {
  const t = useT();
  const intl = useIntl();
  const api = useInventoryApi();
  const locations = useQuery({ queryKey: ["inventory", "locations"], queryFn: () => api.locations(), staleTime: Infinity });

  useEffect(() => {
    // Preselects the caller's one location when the list holds exactly one; a caller with
    // several picks for herself.
    if (!value && locations.data && locations.data.length === 1) {
      onChange(locations.data[0].locationId);
    }
  }, [value, locations.data, onChange]);

  if (locations.isError) {
    // A 403 (permission.denied prt.location.view) or any other failure: an empty select would
    // read as "you have no location", which is not what happened (bug seen live 28 Sep 2026).
    return <p role="alert">{errorText(locations.error, t("inventory.location.list_refused").text)}</p>;
  }

  return (
    <label className="inventory-form-field">
      {t("inventory.field.location").text}
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        {(locations.data ?? []).map((location) => (
          <option key={location.locationId} value={location.locationId}>
            {`${location.locationCode} ${nameOf(location, intl.locale)}`}
          </option>
        ))}
      </select>
    </label>
  );
}
