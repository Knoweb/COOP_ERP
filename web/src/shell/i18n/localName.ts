// A name that the data holds in three languages (a party's legal name, a place, an item), shown
// in the reader's language. The English one is required everywhere and stands in when the
// reader's language has none. Every screen that shows such a name goes through here, so that no
// screen shows the English name to a Sinhala or Tamil reader when the data has theirs.

export function inLocale(locale: string, en: string, si?: string | null, ta?: string | null): string {
  return (locale === "si" ? si : locale === "ta" ? ta : null) || en;
}

/** An item's code and its name in the reader's language: "SKU-1 සම්බා සහල් 5 kg". */
export function skuText(
  sku: { skuCode: string; nameEn: string; nameSi?: string | null; nameTa?: string | null },
  locale: string
): string {
  return `${sku.skuCode} ${inLocale(locale, sku.nameEn, sku.nameSi, sku.nameTa)}`;
}

/**
 * A party's code and legal name in the reader's language, the code left out when the answer
 * has none: a name alone reads better than "null Cooperative Federation".
 */
export function entityText(
  entity: { entityCode?: string | null; legalNameEn: string; legalNameSi?: string | null; legalNameTa?: string | null },
  locale: string
): string {
  const name = inLocale(locale, entity.legalNameEn, entity.legalNameSi, entity.legalNameTa);
  return entity.entityCode ? `${entity.entityCode} ${name}` : name;
}

/** A location's code and its name in the reader's language: "W01 කුරුණෑගල ගබඩාව". */
export function locationText(
  location: { locationCode: string; nameEn: string; nameSi?: string | null; nameTa?: string | null },
  locale: string
): string {
  return `${location.locationCode} ${inLocale(locale, location.nameEn, location.nameSi, location.nameTa)}`;
}
