import { useEffect } from "react";
import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { useT } from "../../shell/i18n/useT";
import { businessToday } from "../../shell/i18n/formats";
import { locationText } from "../../shell/i18n/localName";
import { usePosApi } from "./posApi";
import { errorText } from "./posView";

/**
 * One value of the screen's address (?location=...&day=...&flagged=1), so that a receipt's Back
 * leads to the same list and a link can be shared. Setting one keeps the others.
 */
function useParam(name: string): [string | null, (value: string | null) => void] {
  const [params, setParams] = useSearchParams();
  const set = (value: string | null) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (value) {
          next.set(name, value);
        } else {
          next.delete(name);
        }
        return next;
      },
      { replace: true }
    );
  return [params.get(name), set];
}

/**
 * The shop whose tills the screen shows. Only shops have tills, so only shops are offered; a
 * caller who sees one shop gets it chosen for her.
 */
export function useShop(): [string, (locationId: string) => void] {
  const [locationId, setLocationId] = useParam("location");
  return [locationId ?? "", (next: string) => setLocationId(next || null)];
}

/** The business day the lists show (yyyy-mm-dd): today in the business time zone unless chosen. */
export function useBusinessDay(): [string, (day: string) => void] {
  const [day, setDay] = useParam("day");
  return [day || businessToday(), (next: string) => setDay(next || null)];
}

/** Whether the receipts list keeps only the receipts central flagged. */
export function useFlaggedOnly(): [boolean, (flaggedOnly: boolean) => void] {
  const [flagged, setFlagged] = useParam("flagged");
  return [flagged === "1", (next: boolean) => setFlagged(next ? "1" : null)];
}

export function ShopPicker({ value, onChange }: { value: string; onChange: (locationId: string) => void }) {
  const t = useT();
  const { locale } = useIntl();
  const api = usePosApi();
  const locations = useQuery({ queryKey: ["pos", "locations"], queryFn: () => api.locations(), staleTime: Infinity });
  const shops = (locations.data ?? []).filter((location) => location.locationType === "SHOP");

  useEffect(() => {
    if (!value && shops.length === 1) {
      onChange(shops[0].locationId);
    }
  }, [value, shops, onChange]);

  if (locations.isError) {
    return <p role="alert">{errorText(locations.error, t("pos.location.list_refused").text)}</p>;
  }

  return (
    <label className="pos-field">
      {t("pos.field.shop").text}
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">{t("pos.field.shop.choose").text}</option>
        {shops.map((location) => (
          <option key={location.locationId} value={location.locationId}>
            {locationText(location, locale)}
          </option>
        ))}
      </select>
    </label>
  );
}

/** The business day of the lists, chosen on a calendar. */
export function DayPicker({ value, onChange }: { value: string; onChange: (day: string) => void }) {
  const t = useT();
  return (
    <label className="pos-field">
      {t("pos.field.business_date").text}
      <input type="date" value={value} onChange={(event) => onChange(event.target.value)} />
    </label>
  );
}
