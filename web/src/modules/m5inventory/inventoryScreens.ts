import type { LotBalance, NegativeLot, WriteOff } from "./inventoryApi";

export type ExpiryRow = LotBalance & { daysRemaining: number };

/** Date-only arithmetic keeps the list stable around midnight in Asia/Colombo. */
export function expiryRows(lots: LotBalance[], today: string): ExpiryRow[] {
  const start = Date.parse(`${today}T00:00:00Z`);
  return lots
    .filter((lot) => lot.qtyOnHand > 0 && lot.expiryDate != null)
    .map((lot) => ({
      ...lot,
      daysRemaining: Math.round((Date.parse(`${lot.expiryDate}T00:00:00Z`) - start) / 86_400_000)
    }))
    .sort((left, right) => left.daysRemaining - right.daysRemaining || left.skuId.localeCompare(right.skuId));
}

export type LossCategorySummary = {
  category: WriteOff["category"];
  documents: number;
  value: number;
};

export function lossCategories(writeOffs: WriteOff[]): LossCategorySummary[] {
  const totals = new Map<WriteOff["category"], LossCategorySummary>();
  for (const writeOff of writeOffs.filter((row) => row.status === "POSTED")) {
    const current = totals.get(writeOff.category) ?? { category: writeOff.category, documents: 0, value: 0 };
    current.documents += 1;
    current.value += writeOff.value ?? 0;
    totals.set(writeOff.category, current);
  }
  return [...totals.values()].sort((left, right) => right.value - left.value || left.category.localeCompare(right.category));
}

export function unacknowledgedNegativeLotCount(lots: NegativeLot[]): number {
  return lots.filter((lot) => lot.acknowledgedAt == null).length;
}
