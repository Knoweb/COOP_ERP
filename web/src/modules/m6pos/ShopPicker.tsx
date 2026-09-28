import { useEffect } from "react";
import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { useT } from "../../shell/i18n/useT";
import { locationText } from "../../shell/i18n/localName";
import { usePosApi } from "./posApi";
import { errorText } from "./posView";

/**
 * The shop whose tills the screen shows, kept in the address (?location=...) so that a receipt's
 * Back leads to the same shop's list and a link can be shared. Only shops have tills, so only
 * shops are offered; a caller who sees one shop gets it chosen for her.
 */
export function useShop(): [string, (locationId: string) => void] {
  const [params, setParams] = useSearchParams();
  const locationId = params.get("location") ?? "";
  const choose = (next: string) => setParams(next ? { location: next } : {}, { replace: true });
  return [locationId, choose];
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
