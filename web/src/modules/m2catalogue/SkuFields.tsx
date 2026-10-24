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
        {t("catalogue.field.description_en").text}
        <textarea
          rows={2}
          disabled={disabled}
          value={form.descriptionEn}
          onChange={(event) => onChange({ descriptionEn: event.target.value })}
        />
      </label>
      <label className="catalogue-form-field">
        {t("catalogue.field.description_si").text}
        <textarea
          rows={2}
          disabled={disabled}
          value={form.descriptionSi}
          onChange={(event) => onChange({ descriptionSi: event.target.value })}
        />
      </label>
      <label className="catalogue-form-field">
        {t("catalogue.field.description_ta").text}
        <textarea
          rows={2}
          disabled={disabled}
          value={form.descriptionTa}
          onChange={(event) => onChange({ descriptionTa: event.target.value })}
        />
      </label>
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
      <label className="catalogue-form-field">
        {t("catalogue.field.multi_mrp_policy").text}
        <select
          value={form.multiMrpPolicy}
          onChange={(event) => onChange({ multiMrpPolicy: event.target.value as SkuForm["multiMrpPolicy"] })}
        >
          <option value="AUTO_LOWEST">{t("catalogue.mrp_policy.AUTO_LOWEST").text}</option>
          <option value="BARCODE_RESOLVED">{t("catalogue.mrp_policy.BARCODE_RESOLVED").text}</option>
          <option value="PICKER">{t("catalogue.mrp_policy.PICKER").text}</option>
        </select>
      </label>
      <label className="catalogue-form-field">
        {t("catalogue.field.origin_kind").text}
        <select
          value={form.originKind}
          onChange={(event) => onChange({ originKind: event.target.value as SkuForm["originKind"] })}
        >
          <option value="PURCHASED">{t("catalogue.origin.PURCHASED").text}</option>
          <option value="REPACK_OUTPUT">{t("catalogue.origin.REPACK_OUTPUT").text}</option>
          <option value="LOCAL_SOURCED">{t("catalogue.origin.LOCAL_SOURCED").text}</option>
        </select>
      </label>
      {flag("soldByWeight", "catalogue.field.sold_by_weight")}
      {flag("batchTracked", "catalogue.field.batch_tracked")}
      {flag("expiryTracked", "catalogue.field.expiry_tracked")}
      {flag("hasPrintedMrp", "catalogue.field.printed_mrp")}
      <label className="catalogue-form-field">
        {t("catalogue.field.expiry_warning_days").text}
        <input
          type="number"
          min="0"
          max="32767"
          disabled={disabled}
          value={form.expiryWarningDays}
          onChange={(event) => onChange({ expiryWarningDays: event.target.value })}
        />
      </label>
    </fieldset>
  );
}
