import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";

import { useCatalogueApi } from "./catalogueApi";
import { languageOf, nameIn, type SkuForm } from "./skuView";
import { CatalogueDropdown } from "./CatalogueDropdown";

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
      <CatalogueDropdown
        label={t("catalogue.field.unit").text}
        value={form.baseUomCode}
        disabled={disabled}
        required
        onChange={(val) => onChange({ baseUomCode: val })}
        options={(reference.data?.units ?? [{ uomCode: form.baseUomCode, nameEn: form.baseUomCode, weight: false }]).map((unit) => ({
          value: unit.uomCode,
          label: `${unit.uomCode} ${nameIn(unit, language)}`
        }))}
      />

      {/* Row 3: Tax category | MRP resolution policy */}
      <CatalogueDropdown
        label={t("catalogue.field.tax_category").text}
        value={form.taxCategoryId}
        disabled={disabled}
        required
        placeholder={t("catalogue.field.choose").text}
        onChange={(val) => onChange({ taxCategoryId: val })}
        options={(reference.data?.taxCategories ?? []).map((category) => ({
          value: category.taxCategoryId,
          label: nameIn(category, language)
        }))}
      />
      <CatalogueDropdown
        label={t("catalogue.field.multi_mrp_policy").text}
        value={form.multiMrpPolicy}
        disabled={disabled}
        onChange={(val) => onChange({ multiMrpPolicy: val as SkuForm["multiMrpPolicy"] })}
        options={[
          { value: "AUTO_LOWEST", label: t("catalogue.mrp_policy.AUTO_LOWEST").text },
          { value: "BARCODE_RESOLVED", label: t("catalogue.mrp_policy.BARCODE_RESOLVED").text },
          { value: "PICKER", label: t("catalogue.mrp_policy.PICKER").text }
        ]}
      />

      {/* Row 4: Item origin | Expiry warning window (days) */}
      <CatalogueDropdown
        label={t("catalogue.field.origin_kind").text}
        value={form.originKind}
        disabled={disabled}
        onChange={(val) => onChange({ originKind: val as SkuForm["originKind"] })}
        options={[
          { value: "PURCHASED", label: t("catalogue.origin.PURCHASED").text },
          { value: "REPACK_OUTPUT", label: t("catalogue.origin.REPACK_OUTPUT").text },
          { value: "LOCAL_SOURCED", label: t("catalogue.origin.LOCAL_SOURCED").text }
        ]}
      />
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
