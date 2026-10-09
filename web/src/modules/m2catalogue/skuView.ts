import { ApiProblem } from "../../shell/api/client";
import type { ChipState } from "../../shell/components/StateChip";
import type { Language, Sku, SkuDetailsRequest, SkuStatus } from "./catalogueApi";

/** The look of a SKU's state (doc 30 section 2.2): a draft is being written, LOCAL and SHARED are in use. */
export function chipOf(status: SkuStatus): ChipState {
  switch (status) {
    case "DRAFT":
      return "draft";
    case "LOCAL":
    case "SHARED":
      return "issued";
    default:
      return "void";
  }
}

/** The name in the reader's language, the English one when that language has none. */
export function nameIn(sku: Pick<Sku, "nameEn" | "nameSi" | "nameTa">, language: string): string {
  if (language === "si" && sku.nameSi) {
    return sku.nameSi;
  }
  if (language === "ta" && sku.nameTa) {
    return sku.nameTa;
  }
  return sku.nameEn;
}

export function languageOf(locale: string): Language {
  return locale === "si" || locale === "ta" ? locale : "en";
}

/** The problem's title, which the server has already translated; the fallback otherwise. */
export function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiProblem && error.problem.title ? error.problem.title : fallback;
}

/** The editor's form: every field as the author types it. */
export type SkuForm = {
  nameEn: string;
  nameSi: string;
  nameTa: string;
  baseUomCode: string;
  taxCategoryId: string;
  soldByWeight: boolean;
  batchTracked: boolean;
  expiryTracked: boolean;
  hasPrintedMrp: boolean;
};

export const EMPTY_FORM: SkuForm = {
  nameEn: "",
  nameSi: "",
  nameTa: "",
  baseUomCode: "EA",
  taxCategoryId: "",
  soldByWeight: false,
  batchTracked: false,
  expiryTracked: false,
  hasPrintedMrp: false
};

export function formOf(sku: Sku): SkuForm {
  return {
    nameEn: sku.nameEn,
    nameSi: sku.nameSi ?? "",
    nameTa: sku.nameTa ?? "",
    baseUomCode: sku.baseUomCode,
    taxCategoryId: sku.taxCategoryId,
    soldByWeight: sku.soldByWeight,
    batchTracked: sku.batchTracked,
    expiryTracked: sku.expiryTracked,
    hasPrintedMrp: sku.hasPrintedMrp
  };
}

/**
 * The request of a form. The fields the demo editor does not show keep the SKU's own values on
 * an update, and the catalogue's defaults on a create (AUTO_LOWEST, PURCHASED).
 */
export function requestOf(form: SkuForm, existing?: Sku): SkuDetailsRequest {
  const optional = (text: string) => (text.trim() ? text.trim() : undefined);
  return {
    nameEn: form.nameEn.trim(),
    nameSi: optional(form.nameSi),
    nameTa: optional(form.nameTa),
    descriptionEn: existing?.descriptionEn,
    descriptionSi: existing?.descriptionSi,
    descriptionTa: existing?.descriptionTa,
    baseUomCode: form.baseUomCode,
    soldByWeight: form.soldByWeight,
    batchTracked: form.batchTracked,
    expiryTracked: form.expiryTracked,
    hasPrintedMrp: form.hasPrintedMrp,
    expiryWarningDays: existing?.expiryWarningDays,
    taxCategoryId: form.taxCategoryId,
    multiMrpPolicy: (existing?.multiMrpPolicy as SkuDetailsRequest["multiMrpPolicy"]) ?? "AUTO_LOWEST",
    originKind: (existing?.originKind as SkuDetailsRequest["originKind"]) ?? "PURCHASED",
    attributes: existing?.attributes
  };
}

/**
 * The Idempotency-Key for attaching `fingerprint` (the file's hash and the barcode it goes
 * with). A retry of the same file after a failed storage upload keeps the key, so the server
 * renews the PENDING row; a different file is a new action and gets a new key, so it is never
 * sent with a key the server has seen with another body. `lastSent` remembers what the key was
 * last used for.
 */
export function keyForUpload(
  key: { current: () => string; next: () => void },
  lastSent: { current: string | null },
  fingerprint: string
): string {
  if (lastSent.current !== null && lastSent.current !== fingerprint) {
    key.next();
  }
  lastSent.current = fingerprint;
  return key.current();
}
