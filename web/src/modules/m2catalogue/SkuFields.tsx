import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";

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

  const text = (field: "nameEn" | "nameSi" | "nameTa", id: string, required = false, full = false) => (
    <label className={`catalogue-form-field ${full ? "catalogue-form-field--full" : ""}`}>
      <span>
        {t(id).text}
        {required && <span className="register-field-required">*</span>}
      </span>
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
    <label className="catalogue-checkbox-card">
      <input
        type="checkbox"
        disabled={disabled}
        checked={form[field]}
        onChange={(event) => {
          const checked = event.target.checked;
          if (field === "expiryTracked") {
            onChange({
              expiryTracked: checked,
              ...(checked ? { batchTracked: true } : {})
            });
          } else if (field === "batchTracked" && !checked) {
            onChange({
              batchTracked: false,
              expiryTracked: false
            });
          } else {
            onChange({ [field]: checked });
          }
        }}
      />
      <span>{t(id).text}</span>
    </label>
  );

  return (
    <fieldset disabled={disabled} className="catalogue-fieldset catalogue-form-grid">
      {/* Row 1: Name English | Name Sinhala */}
      {text("nameEn", "catalogue.field.name_en", true)}
      {text("nameSi", "catalogue.field.name_si")}

      {/* Row 2: Name Tamil | Unit */}
      {text("nameTa", "catalogue.field.name_ta")}
      <label className="catalogue-form-field">
        <span>
          {t("catalogue.field.unit").text}
          <span className="register-field-required">*</span>
        </span>
        <select
          value={form.baseUomCode}
          disabled={disabled}
          required
          onChange={(event) => onChange({ baseUomCode: event.target.value })}
        >
          {(reference.data?.units ?? [{ uomCode: form.baseUomCode, nameEn: form.baseUomCode, weight: false }]).map((unit) => (
            <option key={unit.uomCode} value={unit.uomCode}>
              {unit.uomCode} {nameIn(unit, language)}
            </option>
          ))}
        </select>
      </label>

      {/* Row 3: Tax category | MRP resolution policy */}
      <label className="catalogue-form-field">
        <span>
          {t("catalogue.field.tax_category").text}
          <span className="register-field-required">*</span>
        </span>
        <select
          value={form.taxCategoryId}
          disabled={disabled}
          required
          onChange={(event) => onChange({ taxCategoryId: event.target.value })}
        >
          <option value="">{t("catalogue.field.choose").text}</option>
          {(reference.data?.taxCategories ?? []).map((category) => (
            <option key={category.taxCategoryId} value={category.taxCategoryId}>
              {nameIn(category, language)}
            </option>
          ))}
        </select>
      </label>

      <label className="catalogue-form-field">
        <span>{t("catalogue.field.multi_mrp_policy").text}</span>
        <select
          value={form.multiMrpPolicy}
          disabled={disabled}
          onChange={(event) => onChange({ multiMrpPolicy: event.target.value as SkuForm["multiMrpPolicy"] })}
        >
          <option value="AUTO_LOWEST">{t("catalogue.mrp_policy.AUTO_LOWEST").text}</option>
          <option value="BARCODE_RESOLVED">{t("catalogue.mrp_policy.BARCODE_RESOLVED").text}</option>
          <option value="PICKER">{t("catalogue.mrp_policy.PICKER").text}</option>
        </select>
      </label>

      {/* Row 4: Item origin | Expiry warning window (days) */}
      <label className="catalogue-form-field">
        <span>{t("catalogue.field.origin_kind").text}</span>
        <select
          value={form.originKind}
          disabled={disabled}
          onChange={(event) => onChange({ originKind: event.target.value as SkuForm["originKind"] })}
        >
          <option value="PURCHASED">{t("catalogue.origin.PURCHASED").text}</option>
          <option value="REPACK_OUTPUT">{t("catalogue.origin.REPACK_OUTPUT").text}</option>
          <option value="LOCAL_SOURCED">{t("catalogue.origin.LOCAL_SOURCED").text}</option>
        </select>
      </label>

      <label className="catalogue-form-field">
        <span>{t("catalogue.field.expiry_warning_days").text}</span>
        <input
          type="number"
          min="0"
          max="32767"
          disabled={disabled}
          value={form.expiryWarningDays}
          onChange={(event) => onChange({ expiryWarningDays: event.target.value })}
        />
      </label>

      {/* Rows 5 & 6: 4 Checkboxes (2 per row) */}
      <div className="catalogue-flags-grid">
        {flag("soldByWeight", "catalogue.field.sold_by_weight")}
        {flag("batchTracked", "catalogue.field.batch_tracked")}
        {flag("expiryTracked", "catalogue.field.expiry_tracked")}
        {flag("hasPrintedMrp", "catalogue.field.printed_mrp")}
      </div>

      {/* Row 7: Description English | Description Sinhala */}
      <label className="catalogue-form-field">
        <span>{t("catalogue.field.description_en").text}</span>
        <textarea
          rows={2}
          disabled={disabled}
          value={form.descriptionEn}
          onChange={(event) => onChange({ descriptionEn: event.target.value })}
        />
      </label>
      <label className="catalogue-form-field">
        <span>{t("catalogue.field.description_si").text}</span>
        <textarea
          rows={2}
          disabled={disabled}
          value={form.descriptionSi}
          onChange={(event) => onChange({ descriptionSi: event.target.value })}
        />
      </label>

      {/* Row 8: Description Tamil */}
      <label className="catalogue-form-field">
        <span>{t("catalogue.field.description_ta").text}</span>
        <textarea
          rows={2}
          disabled={disabled}
          value={form.descriptionTa}
          onChange={(event) => onChange({ descriptionTa: event.target.value })}
        />
      </label>
    </fieldset>
  );
}
