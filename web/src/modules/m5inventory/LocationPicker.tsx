import { useEffect } from "react";
import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useInventoryApi, type Location } from "./inventoryApi";

function nameOf(location: Location, locale: string): string {
  if (locale === "si" && location.nameSi) {
    return location.nameSi;
  }
  if (locale === "ta" && location.nameTa) {
    return location.nameTa;
  }
  return location.nameEn;
}

/** The locations the caller's scope sees (M1's list); the first is chosen until the user picks. */
export function LocationPicker({ value, onChange }: { value: string; onChange: (locationId: string) => void }) {
  const t = useT();
  const intl = useIntl();
  const api = useInventoryApi();
  const locations = useQuery({ queryKey: ["inventory", "locations"], queryFn: () => api.locations(), staleTime: Infinity });

  useEffect(() => {
    if (!value && locations.data && locations.data.length > 0) {
      onChange(locations.data[0].locationId);
    }
  }, [value, locations.data, onChange]);

  return (
    <label style={{ display: "grid", gap: "var(--space-half)" }}>
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
