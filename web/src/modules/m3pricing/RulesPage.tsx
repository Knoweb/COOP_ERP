import { useState } from "react";
import type { FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { businessToday, formatMoneyDigits, useFormatDate } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip } from "../../shell/components/StateChip";
import { usePricingApi } from "./pricingApi";
import type { AuthorRuleRequest, Rule, Sku } from "./pricingApi";
import { SkuName, SkuPicker } from "./SkuPicker";
import { errorText, ruleStatusChip } from "./priceListState";
import { PricingTabs } from "./PricingTabs";
import "./pricing.css";

type RuleKind = "TIME_LIMITED_PRICE" | "QUANTITY_BREAK" | "BILL_THRESHOLD" | "EXPIRY_MARKDOWN";
type BenefitKind = "FIXED_PRICE" | "PERCENT_OFF" | "AMOUNT_OFF";

const ALLOWED_BENEFITS: Record<RuleKind, BenefitKind[]> = {
  TIME_LIMITED_PRICE: ["FIXED_PRICE", "PERCENT_OFF", "AMOUNT_OFF"],
  QUANTITY_BREAK: ["PERCENT_OFF", "FIXED_PRICE"],
  BILL_THRESHOLD: ["PERCENT_OFF", "AMOUNT_OFF"],
  EXPIRY_MARKDOWN: ["PERCENT_OFF", "FIXED_PRICE"]
};

/**
 * Promotion and discount rules screen (/pricing/rules; 23A section 8; doc 23 section 3.3).
 * MPCS users author draft promotion rules, activate them, and view existing rules by status/kind.
 */
export function RulesPage() {
  const t = useT();
  const formatDate = useFormatDate();
  const api = usePricingApi();
  const queryClient = useQueryClient();
  const canAuthor = useHasPermission("prc.rule.author");
  const canActivate = useHasPermission("prc.rule.activate");

  const [statusFilter, setStatusFilter] = useState<string>("ALL");
  const [kindFilter, setKindFilter] = useState<string>("ALL");
  const [withdrawingRuleId, setWithdrawingRuleId] = useState<string | null>(null);
  const [withdrawReason, setWithdrawReason] = useState("");
  const [actionError, setActionError] = useState<string | null>(null);

  const key = useIdempotencyKey();

  const rulesQuery = useQuery({
    queryKey: ["pricing", "rules", statusFilter, kindFilter],
    queryFn: () =>
      api.listRules({
        status: statusFilter !== "ALL" ? (statusFilter as Rule["status"]) : undefined,
        kind: kindFilter !== "ALL" ? (kindFilter as Rule["kind"]) : undefined
      })
  });

  const activateMutation = useMutation({
    mutationFn: (ruleId: string) => api.activateRule(ruleId, key.current()),
    onSuccess: () => {
      key.next();
      setActionError(null);
      void queryClient.invalidateQueries({ queryKey: ["pricing", "rules"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
      setActionError(errorText(error, t("pricing.error.generic").text));
    }
  });

  const withdrawMutation = useMutation({
    mutationFn: ({ ruleId, reason }: { ruleId: string; reason: string }) =>
      api.withdrawRule(ruleId, { reason }, key.current()),
    onSuccess: () => {
      key.next();
      setWithdrawingRuleId(null);
      setWithdrawReason("");
      setActionError(null);
      void queryClient.invalidateQueries({ queryKey: ["pricing", "rules"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
      setActionError(errorText(error, t("pricing.error.generic").text));
    }
  });

  const handleActivate = (ruleId: string) => {
    setActionError(null);
    activateMutation.mutate(ruleId);
  };

  const handleWithdrawSubmit = (event: FormEvent, ruleId: string) => {
    event.preventDefault();
    if (!withdrawReason.trim()) return;
    withdrawMutation.mutate({ ruleId, reason: withdrawReason.trim() });
  };

  return (
    <main className="shell-page">
      <h1>{t("pricing.rules.title").text}</h1>
      <PricingTabs />
      <p className="modern-intro-text">{t("pricing.rules.intro").text}</p>

      {canAuthor && (
        <AuthorRuleForm
          onCreated={() => {
            void queryClient.invalidateQueries({ queryKey: ["pricing", "rules"] });
          }}
        />
      )}

      {actionError && (
        <p className="pricing-alert-text" role="alert">
          {actionError}
        </p>
      )}

      <section className="pricing-section">
        <div className="pricing-filter-bar">
          <label className="pricing-form-field">
            <span className="pricing-filter-label">{t("pricing.rules.filter_status").text}</span>
            <select
              aria-label={t("pricing.rules.filter_status").text}
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
            >
              <option value="ALL">{t("pricing.rules.status.ALL").text}</option>
              <option value="DRAFT">{t("pricing.rules.status.DRAFT").text}</option>
              <option value="ACTIVE">{t("pricing.rules.status.ACTIVE").text}</option>
              <option value="EXPIRED">{t("pricing.rules.status.EXPIRED").text}</option>
              <option value="WITHDRAWN">{t("pricing.rules.status.WITHDRAWN").text}</option>
            </select>
          </label>

          <label className="pricing-form-field">
            <span className="pricing-filter-label">{t("pricing.rules.filter_kind").text}</span>
            <select
              aria-label={t("pricing.rules.filter_kind").text}
              value={kindFilter}
              onChange={(e) => setKindFilter(e.target.value)}
            >
              <option value="ALL">{t("pricing.rules.kind.ALL").text}</option>
              <option value="TIME_LIMITED_PRICE">{t("pricing.rules.kind.TIME_LIMITED_PRICE").text}</option>
              <option value="QUANTITY_BREAK">{t("pricing.rules.kind.QUANTITY_BREAK").text}</option>
              <option value="BILL_THRESHOLD">{t("pricing.rules.kind.BILL_THRESHOLD").text}</option>
              <option value="EXPIRY_MARKDOWN">{t("pricing.rules.kind.EXPIRY_MARKDOWN").text}</option>
            </select>
          </label>
        </div>

        {rulesQuery.isLoading && <p>{t("pricing.list.loading").text}</p>}
        {rulesQuery.isError && <p role="alert">{errorText(rulesQuery.error, t("pricing.error.generic").text)}</p>}
        {rulesQuery.data?.length === 0 && <div className="modern-empty-state">{t("pricing.rules.empty").text}</div>}

        {rulesQuery.data && rulesQuery.data.length > 0 && (
          <div className="modern-table-card">
            <div className="modern-table-scroll">
              <table className="modern-table">
                <thead>
                  <tr>
                    <th>{t("pricing.rules.column.name").text}</th>
                    <th>{t("pricing.rules.column.kind").text}</th>
                    <th>{t("pricing.rules.column.target").text}</th>
                    <th>{t("pricing.rules.column.benefit").text}</th>
                    <th>{t("pricing.rules.column.priority").text}</th>
                    <th>{t("pricing.rules.column.validity").text}</th>
                    <th>{t("pricing.rules.column.status").text}</th>
                    {canActivate && <th>{t("pricing.rules.column.actions").text}</th>}
                  </tr>
                </thead>
                <tbody>
                  {rulesQuery.data.map((rule) => (
                    <tr key={rule.ruleId}>
                      <td>
                        <strong>{rule.name}</strong>
                      </td>
                      <td>{t(`pricing.rules.kind.${rule.kind}`).text}</td>
                      <td>
                        <RuleTarget predicate={rule.predicate} kind={rule.kind as RuleKind} />
                      </td>
                      <td>
                        <RuleBenefitDisplay benefit={rule.benefit} />
                      </td>
                      <td>{rule.priority}</td>
                      <td>
                        {formatDate(rule.validFrom)}
                        {rule.validTo ? ` — ${formatDate(rule.validTo)}` : ""}
                      </td>
                      <td>
                        <StateChip
                          state={ruleStatusChip(rule.status)}
                          label={t(`pricing.rules.status.${rule.status}`).text}
                        />
                      </td>
                      {canActivate && (
                        <td>
                          {rule.status === "DRAFT" && (
                            <button
                              type="button"
                              className="btn btn--secondary btn--sm"
                              onClick={() => handleActivate(rule.ruleId)}
                              disabled={activateMutation.isPending}
                            >
                              {t("pricing.rules.action.activate").text}
                            </button>
                          )}
                          {(rule.status === "DRAFT" || rule.status === "ACTIVE") && (
                            <>
                              {withdrawingRuleId === rule.ruleId ? (
                                <form
                                  className="pricing-withdraw-form"
                                  onSubmit={(e) => handleWithdrawSubmit(e, rule.ruleId)}
                                >
                                  <input
                                    type="text"
                                    placeholder={t("pricing.rules.withdraw_reason_placeholder").text}
                                    value={withdrawReason}
                                    onChange={(e) => setWithdrawReason(e.target.value)}
                                    required
                                    maxLength={200}
                                  />
                                  <button
                                    type="submit"
                                    className="btn btn--danger btn--sm"
                                    disabled={withdrawMutation.isPending}
                                  >
                                    {t("pricing.rules.action.confirm_withdraw").text}
                                  </button>
                                  <button
                                    type="button"
                                    className="btn btn--sm"
                                    onClick={() => {
                                      setWithdrawingRuleId(null);
                                      setWithdrawReason("");
                                    }}
                                  >
                                    {t("pricing.cancel").text}
                                  </button>
                                </form>
                              ) : (
                                <button
                                  type="button"
                                  className="btn btn--sm"
                                  onClick={() => {
                                    setWithdrawingRuleId(rule.ruleId);
                                    setWithdrawReason("");
                                  }}
                                >
                                  {t("pricing.rules.action.withdraw").text}
                                </button>
                              )}
                            </>
                          )}
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>
    </main>
  );
}

function RuleTarget({ predicate, kind }: { predicate: Rule["predicate"]; kind: RuleKind }) {
  const t = useT();
  if (kind === "BILL_THRESHOLD" && predicate.billTotalFrom != null) {
    return (
      <span>
        {t("pricing.rules.target.bill_from").text} <MoneyDisplay amount={predicate.billTotalFrom} />
      </span>
    );
  }
  return (
    <div>
      {predicate.skuId && <SkuName skuId={predicate.skuId} />}
      {kind === "QUANTITY_BREAK" && predicate.minQty != null && (
        <span className="pricing-muted">
          {" "}
          (≥ {predicate.minQty})
        </span>
      )}
      {kind === "EXPIRY_MARKDOWN" && predicate.daysToExpiry != null && (
        <span className="pricing-muted">
          {" "}
          (≤ {predicate.daysToExpiry} {t("pricing.rules.days").text})
        </span>
      )}
    </div>
  );
}

function RuleBenefitDisplay({ benefit }: { benefit: Rule["benefit"] }) {
  const t = useT();
  switch (benefit.kind) {
    case "PERCENT_OFF":
      return <span>{t("pricing.rules.benefit.percent_off", undefined, { percent: benefit.value }).text}</span>;
    case "FIXED_PRICE":
      return (
        <span>
          {t("pricing.rules.benefit.fixed").text} <MoneyDisplay amount={benefit.value} />
        </span>
      );
    case "AMOUNT_OFF":
      return (
        <span>
          {t("pricing.rules.benefit.amount_off", undefined, { amount: formatMoneyDigits(benefit.value)?.digits ?? benefit.value }).text}
        </span>
      );
    default:
      return <span>{benefit.value}</span>;
  }
}

function AuthorRuleForm({ onCreated }: { onCreated: () => void }) {
  const t = useT();
  const api = usePricingApi();
  const key = useIdempotencyKey();
  const today = businessToday();

  const [name, setName] = useState("");
  const [kind, setKind] = useState<RuleKind>("TIME_LIMITED_PRICE");
  const [sku, setSku] = useState<Sku | null>(null);
  const [uomCode, setUomCode] = useState("");
  const [minQty, setMinQty] = useState("");
  const [daysToExpiry, setDaysToExpiry] = useState("");
  const [billTotalFrom, setBillTotalFrom] = useState("");

  const [benefitKind, setBenefitKind] = useState<BenefitKind>("PERCENT_OFF");
  const [benefitValue, setBenefitValue] = useState("");
  const [priority, setPriority] = useState("100");
  const [validFrom, setValidFrom] = useState(today);
  const [validTo, setValidTo] = useState("");
  const [error, setError] = useState<string | null>(null);

  const handleKindChange = (nextKind: RuleKind) => {
    setKind(nextKind);
    const allowed = ALLOWED_BENEFITS[nextKind];
    if (!allowed.includes(benefitKind)) {
      setBenefitKind(allowed[0]);
    }
  };

  const createMutation = useMutation({
    mutationFn: (body: AuthorRuleRequest) => api.authorRule(body, key.current()),
    onSuccess: () => {
      key.next();
      setName("");
      setSku(null);
      setUomCode("");
      setMinQty("");
      setDaysToExpiry("");
      setBillTotalFrom("");
      setBenefitValue("");
      setPriority("100");
      setValidFrom(today);
      setValidTo("");
      setError(null);
      onCreated();
    },
    onError: (err) => {
      if (err instanceof ApiProblem) {
        key.next();
      }
      setError(errorText(err, t("pricing.error.generic").text));
    }
  });

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    setError(null);

    const val = Number(benefitValue);
    if (!benefitValue || isNaN(val) || val <= 0) {
      setError(t("pricing.rules.error.benefit_value_invalid").text);
      return;
    }
    if (benefitKind === "PERCENT_OFF" && val >= 100) {
      setError(t("pricing.rules.error.percent_under_100").text);
      return;
    }

    if (kind === "BILL_THRESHOLD") {
      const threshold = Number(billTotalFrom);
      if (!billTotalFrom || isNaN(threshold) || threshold <= 0) {
        setError(t("pricing.rules.error.bill_total_required").text);
        return;
      }
    } else {
      if (!sku) {
        setError(t("pricing.rules.error.sku_required").text);
        return;
      }
      if (kind === "QUANTITY_BREAK") {
        const qty = Number(minQty);
        if (!minQty || isNaN(qty) || qty <= 0) {
          setError(t("pricing.rules.error.min_qty_required").text);
          return;
        }
      } else if (kind === "EXPIRY_MARKDOWN") {
        const days = Number(daysToExpiry);
        if (daysToExpiry === "" || isNaN(days) || days < 0) {
          setError(t("pricing.rules.error.days_to_expiry_required").text);
          return;
        }
      }
    }

    const requestBody: AuthorRuleRequest = {
      name: name.trim(),
      kind,
      predicate: {
        skuId: kind !== "BILL_THRESHOLD" && sku ? sku.skuId : null,
        uomCode: kind === "TIME_LIMITED_PRICE" && uomCode.trim() ? uomCode.trim() : null,
        minQty: kind === "QUANTITY_BREAK" && minQty ? Number(minQty) : null,
        daysToExpiry: kind === "EXPIRY_MARKDOWN" && daysToExpiry !== "" ? Number(daysToExpiry) : null,
        billTotalFrom: kind === "BILL_THRESHOLD" && billTotalFrom ? Number(billTotalFrom) : null
      },
      benefit: {
        kind: benefitKind,
        value: val
      },
      priority: priority.trim() ? Number(priority) : 100,
      validFrom,
      validTo: validTo || null
    };

    createMutation.mutate(requestBody);
  };

  return (
    <section className="modern-card pricing-section">
      <h2>{t("pricing.rules.author_title").text}</h2>

      {error && (
        <p className="pricing-alert-text" role="alert">
          {error}
        </p>
      )}

      <div className="pricing-filter-bar">
        <label className="pricing-form-field">
          <span>{t("pricing.rules.field.name").text}</span>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            maxLength={120}
            placeholder={t("pricing.rules.field.name_placeholder").text}
          />
        </label>

        <label className="pricing-form-field">
          <span>{t("pricing.rules.field.kind").text}</span>
          <select
            aria-label={t("pricing.rules.field.kind").text}
            value={kind}
            onChange={(e) => handleKindChange(e.target.value as RuleKind)}
          >
            <option value="TIME_LIMITED_PRICE">{t("pricing.rules.kind.TIME_LIMITED_PRICE").text}</option>
            <option value="QUANTITY_BREAK">{t("pricing.rules.kind.QUANTITY_BREAK").text}</option>
            <option value="BILL_THRESHOLD">{t("pricing.rules.kind.BILL_THRESHOLD").text}</option>
            <option value="EXPIRY_MARKDOWN">{t("pricing.rules.kind.EXPIRY_MARKDOWN").text}</option>
          </select>
        </label>
      </div>

      {kind !== "BILL_THRESHOLD" && (
        <div className="pricing-form-field">
          <span>{t("pricing.column.item").text}</span>
          {sku ? (
            <p className="pricing-header-row">
              <SkuName skuId={sku.skuId} />
              <button type="button" onClick={() => setSku(null)}>
                {t("pricing.gazette.change_item").text}
              </button>
            </p>
          ) : (
            <SkuPicker onPick={setSku} />
          )}
        </div>
      )}

      <form onSubmit={handleSubmit}>
        <div className="pricing-filter-bar">
          {kind === "TIME_LIMITED_PRICE" && (
            <label className="pricing-form-field">
              <span>{t("pricing.rules.field.uom").text}</span>
              <input
                type="text"
                value={uomCode}
                onChange={(e) => setUomCode(e.target.value)}
                placeholder={sku?.baseUomCode ?? "e.g. EACH"}
                maxLength={10}
              />
            </label>
          )}

          {kind === "QUANTITY_BREAK" && (
            <label className="pricing-form-field">
              <span>{t("pricing.rules.field.min_qty").text}</span>
              <input
                type="number"
                step="0.001"
                min="0.001"
                value={minQty}
                onChange={(e) => setMinQty(e.target.value)}
                required
              />
            </label>
          )}

          {kind === "BILL_THRESHOLD" && (
            <label className="pricing-form-field">
              <span>{t("pricing.rules.field.bill_total_from").text}</span>
              <input
                type="number"
                step="0.01"
                min="0.01"
                value={billTotalFrom}
                onChange={(e) => setBillTotalFrom(e.target.value)}
                required
              />
            </label>
          )}

          {kind === "EXPIRY_MARKDOWN" && (
            <label className="pricing-form-field">
              <span>{t("pricing.rules.field.days_to_expiry").text}</span>
              <input
                type="number"
                step="1"
                min="0"
                max="3650"
                value={daysToExpiry}
                onChange={(e) => setDaysToExpiry(e.target.value)}
                required
              />
            </label>
          )}

          <label className="pricing-form-field">
            <span>{t("pricing.rules.field.benefit_kind").text}</span>
            <select
              aria-label={t("pricing.rules.field.benefit_kind").text}
              value={benefitKind}
              onChange={(e) => setBenefitKind(e.target.value as BenefitKind)}
            >
              {ALLOWED_BENEFITS[kind].map((bk) => (
                <option key={bk} value={bk}>
                  {t(`pricing.rules.benefit_kind.${bk}`).text}
                </option>
              ))}
            </select>
          </label>

          <label className="pricing-form-field">
            <span>{t("pricing.rules.field.benefit_value").text}</span>
            <input
              type="number"
              step="0.01"
              min="0.01"
              max={benefitKind === "PERCENT_OFF" ? "99.99" : undefined}
              value={benefitValue}
              onChange={(e) => setBenefitValue(e.target.value)}
              required
            />
          </label>

          <label className="pricing-form-field">
            <span>{t("pricing.rules.field.priority").text}</span>
            <input
              type="number"
              min="0"
              max="32767"
              value={priority}
              onChange={(e) => setPriority(e.target.value)}
            />
          </label>
        </div>

        <div className="pricing-filter-bar">
          <label className="pricing-form-field">
            <span>{t("pricing.rules.field.valid_from").text}</span>
            <input
              type="date"
              value={validFrom}
              onChange={(e) => setValidFrom(e.target.value)}
              required
            />
          </label>

          <label className="pricing-form-field">
            <span>{t("pricing.rules.field.valid_to").text}</span>
            <input
              type="date"
              value={validTo}
              onChange={(e) => setValidTo(e.target.value)}
            />
          </label>

          <div className="pricing-form-field" style={{ alignSelf: "flex-end" }}>
            <button type="submit" className="btn btn--primary" disabled={createMutation.isPending}>
              {t("pricing.rules.action.save_draft").text}
            </button>
          </div>
        </div>
      </form>
    </section>
  );
}
