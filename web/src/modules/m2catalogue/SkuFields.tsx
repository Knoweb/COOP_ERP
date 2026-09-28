import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import "./catalogue.css";
import { useCatalogueApi } from "./catalogueApi";
import { languageOf, nameIn, type SkuForm } from "./skuView";

type Props = { form: SkuForm; onChange: (change: Partial<SkuForm>) => void; disabled?: boolean };

/**
 * The fields of the SKU editor (22A section 8, "SKU editor", demo scope): the three names, the
 * base unit, the tax category and the tracking flags. Units and tax categories come from the
 * catalogue's reference read; nothing is typed as a code.
 */
export function SkuFields({ form, onChange, disabled }: Props) {
  const t = useT();
  const intl = useIntl();
  const api = useCatalogueApi();
  const language = languageOf(intl.locale);
  const reference = useQuery({ queryKey: ["catalogue", "reference"], queryFn: () => api.reference(), staleTime: Infinity });

  const text = (field: "nameEn" | "nameSi" | "nameTa", id: string, required = false) => (
    <label className="catalogue-form-field">
      {t(id).text}
      <input
        type="text"
        maxLength={80}
        required={required}
        disabled={disabled}
        value={form[field]}
        onChange={(event) => onChange({ [field]: event.target.value })}
      />
    </label>
  );
  const flag = (field: "soldByWeight" | "batchTracked" | "expiryTracked" | "hasPrintedMrp", id: string) => (
    <label className="catalogue-inline-field">
      <input type="checkbox" disabled={disabled} checked={form[field]} onChange={(event) => onChange({ [field]: event.target.checked })} />
      {t(id).text}
    </label>
  );

  return (
    <fieldset disabled={disabled} className="catalogue-fieldset">
      {text("nameEn", "catalogue.field.name_en", true)}
      {text("nameSi", "catalogue.field.name_si")}
      {text("nameTa", "catalogue.field.name_ta")}
      <label className="catalogue-form-field">
        {t("catalogue.field.unit").text}
        <select required value={form.baseUomCode} onChange={(event) => onChange({ baseUomCode: event.target.value })}>
          {(reference.data?.units ?? [{ uomCode: form.baseUomCode, nameEn: form.baseUomCode, weight: false }]).map((unit) => (
            <option key={unit.uomCode} value={unit.uomCode}>
              {`${unit.uomCode} ${nameIn(unit, language)}`}
            </option>
          ))}
        </select>
      </label>
      <label className="catalogue-form-field">
        {t("catalogue.field.tax_category").text}
        <select required value={form.taxCategoryId} onChange={(event) => onChange({ taxCategoryId: event.target.value })}>
          <option value="">{t("catalogue.field.choose").text}</option>
          {(reference.data?.taxCategories ?? []).map((category) => (
            <option key={category.taxCategoryId} value={category.taxCategoryId}>
              {nameIn(category, language)}
            </option>
          ))}
        </select>
      </label>
      {flag("soldByWeight", "catalogue.field.sold_by_weight")}
      {flag("batchTracked", "catalogue.field.batch_tracked")}
      {flag("expiryTracked", "catalogue.field.expiry_tracked")}
      {flag("hasPrintedMrp", "catalogue.field.printed_mrp")}
    </fieldset>
  );
}
