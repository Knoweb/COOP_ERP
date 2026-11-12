import { useState } from "react";
import type { FormEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useFormatInstant } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";
import {
  useCustomersApi,
  type CustomerCard,
  type RecordConsentRequest
} from "./customersApi";
import { errorText } from "./customersView";

const PURPOSES = [
  "CREDIT_ACCOUNT",
  "STATEMENTS_NOTIFICATIONS"
] as const;

type Purpose = RecordConsentRequest["purpose"];
type Channel = RecordConsentRequest["via"];

export function ConsentManagement({
  customer,
  canManage
}: {
  customer: CustomerCard;
  canManage: boolean;
}) {
  const t = useT();
  const api = useCustomersApi();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const formatInstant = useFormatInstant();

  const [purpose, setPurpose] = useState<Purpose | "">("");
  const [via, setVia] = useState<Channel>("PAPER");

  const customerId = customer.customerId;
  const active = new Set(
    customer.consents
      .filter((consent) => !consent.withdrawnAt)
      .map((consent) => consent.purpose)
  );

  const available = PURPOSES.filter((item) => !active.has(item));
  const canEdit = canManage &&
    customer.registeredHere &&
    customer.status !== "ANONYMISED";

  const completed = (updated: CustomerCard) => {
    key.next();
    setPurpose("");
    queryClient.setQueryData(["customers", "card", customerId], updated);
    queryClient.invalidateQueries({
      queryKey: ["customers", "card", customerId]
    });
  };

  const failed = (error: unknown) => {
    if (error instanceof ApiProblem) key.next();
  };

  const record = useMutation({
    mutationFn: () =>
      api.recordConsent(
        customerId,
        { purpose: purpose as Purpose, via },
        key.current()
      ),
    onSuccess: completed,
    onError: failed
  });

  const withdraw = useMutation({
    mutationFn: (selected: Purpose) =>
      api.withdrawConsent(customerId, selected, key.current()),
    onSuccess: completed,
    onError: failed
  });

  const busy = record.isPending || withdraw.isPending;
  const error = record.error ?? withdraw.error;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!purpose || busy) return;
    withdraw.reset();
    record.mutate();
  };

  return (
    <section className="customers-section" aria-labelledby="customers-consent-title">
      <h2 id="customers-consent-title">
        {t("customers.consent.manage.title").text}
      </h2>

      {customer.consents.length === 0 ? (
        <p>{t("customers.consent.manage.empty").text}</p>
      ) : (
        <ul className="customers-list">
          {customer.consents.map((consent, index) => {
            const isActive = !consent.withdrawnAt;
            const consentPurpose = consent.purpose as Purpose;

            return (
              <li key={`${consent.purpose}-${consent.grantedAt}-${index}`}>
                <strong>
                  {t(`customers.consent.purpose.${consent.purpose}`).text}
                </strong>
                {" · "}
                {isActive
                  ? t("customers.consent.manage.active").text
                  : t("customers.consent.manage.withdrawn").text}
                {" · "}
                {formatInstant(consent.grantedAt)}
                {" · "}
                {consent.grantedVia}

                {consent.withdrawnAt && (
                  <span>
                    {" · "}
                    {t("customers.consent.manage.withdrawn_on").text}
                    {" "}
                    {formatInstant(consent.withdrawnAt)}
                  </span>
                )}

                {isActive && canEdit && (
                  <button
                    type="button"
                    disabled={busy}
                    aria-label={`${t("customers.consent.manage.withdraw").text}: ${t(`customers.consent.purpose.${consent.purpose}`).text}`}
                    onClick={() => {
                      record.reset();
                      withdraw.mutate(consentPurpose);
                    }}
                  >
                    {t("customers.consent.manage.withdraw").text}
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {canEdit && customer.status === "ACTIVE" && available.length > 0 && (
        <form
          className="customers-form customers-subsection"
          onSubmit={submit}
        >
          <h3>{t("customers.consent.manage.record").text}</h3>

          <label className="customers-field">
            {t("customers.consent.manage.purpose").text}
            <select
              required
              value={purpose}
              onChange={(event) => setPurpose(event.target.value as Purpose | "")}
            >
              <option value="">
                {t("customers.status_action.choose").text}
              </option>
              {available.map((item) => (
                <option key={item} value={item}>
                  {t(`customers.consent.purpose.${item}`).text}
                </option>
              ))}
            </select>
          </label>

          <label className="customers-field">
            {t("customers.consent.manage.via").text}
            <select
              value={via}
              onChange={(event) => setVia(event.target.value as Channel)}
            >
              <option value="PAPER">
                {t("customers.consent.manage.paper").text}
              </option>
              <option value="WEB">
                {t("customers.consent.manage.web").text}
              </option>
            </select>
          </label>

          <button type="submit" disabled={busy || !purpose}>
            {t("customers.consent.manage.record").text}
          </button>
        </form>
      )}

      {error != null && (
        <p role="alert">
          {errorText(error, t("customers.error.generic").text)}
        </p>
      )}
    </section>
  );
}
