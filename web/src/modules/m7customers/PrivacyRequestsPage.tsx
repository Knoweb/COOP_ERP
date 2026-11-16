import { useState } from "react";
import type { FormEvent } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { PageHeader } from "../../shell/components/PageHeader";
import { StateChip } from "../../shell/components/StateChip";
import { useFormatInstant } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";
import { CustomSelect } from "./CustomSelect";
import { useCustomersApi, type PrivacyRequest } from "./customersApi";
import { MFA_REQUIRED, errorText, problemCode } from "./customersView";
import "./customers.css";

const KINDS = ["ACCESS", "CORRECTION", "ERASURE"] as const;
type Kind = (typeof KINDS)[number];

/**
 * The society's data-subject requests (27A section 8, "Privacy requests"; doc 27 flow 6.7): the
 * list, and for the responsible officer the answer to each: fulfil (an access export to hand
 * over, a correction written down, an erasure that anonymises the member once nothing is owed) or
 * refuse on a legal ground. The server checks that the answer is the officer's; the screen only
 * offers it to whoever holds cus.privacy.fulfil.
 */
export function PrivacyRequestsPage() {
  const t = useT();
  const api = useCustomersApi();
  const canAnswer = useHasPermission("cus.privacy.fulfil");
  const requests = useQuery({ queryKey: ["customers", "privacy"], queryFn: () => api.privacyRequests() });

  return (
    <main className="shell-page">
      <Link className="back-link" to="/customers">
        <svg viewBox="0 0 24 24">
          <path d="M15 18l-6-6 6-6" />
          <path d="M9 12h10" />
        </svg>
        {t("customers.back").text}
      </Link>
      <PageHeader icon="privacy" title={t("customers.privacy.title").text} />
      <div className="privacy-intro-card">
        <span className="privacy-intro-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
          </svg>
        </span>
        <p className="privacy-intro-text">{t("customers.privacy.explain").text}</p>
      </div>

      {requests.isLoading && (
        <div className="customers-state-box">
          <p>{t("customers.loading").text}</p>
        </div>
      )}

      {requests.isError && (
        <div className="customers-state-box customers-state-box--error" role="alert">
          <p>{errorText(requests.error, t("customers.error.generic").text)}</p>
        </div>
      )}

      {requests.data && requests.data.length === 0 && (
        <div className="customers-state-box customers-state-box--empty privacy-empty-box">
          <span className="privacy-empty-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
            </svg>
          </span>
          <p>{t("customers.privacy.empty").text}</p>
        </div>
      )}

      {requests.data && requests.data.length > 0 && (
        <ul className="customers-list privacy-requests-list">
          {requests.data.map((request) => (
            <RequestRow key={request.requestId} request={request} canAnswer={canAnswer} />
          ))}
        </ul>
      )}
    </main>
  );
}

function RequestRow({ request, canAnswer }: { request: PrivacyRequest; canAnswer: boolean }) {
  const t = useT();
  const api = useCustomersApi();
  const formatInstant = useFormatInstant();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const [text, setText] = useState("");
  const done = () => {
    key.next();
    setText("");
    queryClient.invalidateQueries({ queryKey: ["customers"] });
  };
  const onError = (error: unknown) => {
    if (error instanceof ApiProblem) {
      key.next();
    }
  };
  const fulfil = useMutation({
    mutationFn: () => api.fulfilPrivacyRequest(request.requestId, text.trim() || null, key.current()),
    onSuccess: done,
    onError
  });
  const refuse = useMutation({
    mutationFn: () => api.refusePrivacyRequest(request.requestId, text.trim(), key.current()),
    onSuccess: done,
    onError
  });
  const downloadKey = useIdempotencyKey();
  const download = async () => {
    // Each hand-over is its own audited command: a fresh key per click.
    const data = await api.privacyExport(request.requestId, downloadKey.current());
    downloadKey.next();
    if (!data) {
      return;
    }
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = `access-export-${request.requestId}.json`;
    link.click();
    URL.revokeObjectURL(url);
  };
  const failed = fulfil.error ?? refuse.error;
  const status = request.status === "RECEIVED" ? "draft" : request.status === "FULFILLED" ? "issued" : "void";

  return (
    <li className="privacy-request-card">
      <div className="privacy-card-header">
        <div className="privacy-card-meta">
          <Link to={`/customers/${request.customerId}`} className="privacy-member-link">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
              <circle cx="12" cy="7" r="4" />
            </svg>
            <span>{request.customerName}</span>
          </Link>

          <span className={`privacy-kind-badge privacy-kind-badge--${request.kind.toLowerCase()}`}>
            {t(`customers.privacy.kind.${request.kind}`).text}
          </span>

          <span className="privacy-received-time">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <circle cx="12" cy="12" r="10" />
              <polyline points="12 6 12 12 16 14" />
            </svg>
            <span>{formatInstant(request.receivedAt)}</span>
          </span>
        </div>

        <StateChip state={status} label={t(`customers.privacy.status.${request.status}`).text} />
      </div>

      <div className="privacy-card-content">
        {request.notes && (
          <div className="privacy-info-block">
            <span className="privacy-info-title">{t("customers.privacy.record.notes").text}</span>
            <p className="privacy-info-text">{request.notes}</p>
          </div>
        )}

        {request.outcome && (
          <div className="privacy-info-block privacy-info-block--outcome">
            <span className="privacy-info-title">{t("customers.privacy.answer").text}</span>
            <p className="privacy-info-text">{request.outcome}</p>
          </div>
        )}

        {request.refusalGround && (
          <div className="privacy-info-block privacy-info-block--ground">
            <span className="privacy-info-title">{t("customers.privacy.ground").text}</span>
            <p className="privacy-info-text">{request.refusalGround}</p>
          </div>
        )}

        {request.kind === "ACCESS" && request.status === "FULFILLED" && canAnswer && (
          <div className="privacy-action-download">
            <button type="button" className="privacy-btn-download" onClick={() => void download()}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="7 10 12 15 17 10" />
                <line x1="12" y1="15" x2="12" y2="3" />
              </svg>
              <span>{t("customers.privacy.download").text}</span>
            </button>
          </div>
        )}

        {canAnswer && request.status === "RECEIVED" && (
          <form
            className="customers-form privacy-fulfill-form"
            onSubmit={(event: FormEvent) => {
              event.preventDefault();
              fulfil.mutate();
            }}
          >
            {request.kind === "ERASURE" && (
              <div className="customers-confirm-callout privacy-erasure-warning" role="note">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
                  <line x1="12" y1="9" x2="12" y2="13" />
                  <line x1="12" y1="17" x2="12.01" y2="17" />
                </svg>
                <span>{t("customers.privacy.erasure_warning").text}</span>
              </div>
            )}

            <label className="customers-field">
              <span className="customers-field-label">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
                  <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
                </svg>
                <span>{t("customers.privacy.answer").text}</span>
              </span>
              <input
                maxLength={500}
                aria-label={t("customers.privacy.answer").text}
                value={text}
                onChange={(e) => setText(e.target.value)}
              />
            </label>

            <div className="privacy-actions-group">
              <button
                type="submit"
                className="privacy-btn-fulfil"
                disabled={fulfil.isPending || (request.kind === "CORRECTION" && text.trim() === "")}
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <polyline points="20 6 9 17 4 12" />
                </svg>
                <span>{t("customers.privacy.fulfil").text}</span>
              </button>
              <button
                type="button"
                className="privacy-btn-refuse"
                disabled={refuse.isPending || text.trim() === ""}
                onClick={() => refuse.mutate()}
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <circle cx="12" cy="12" r="10" />
                  <line x1="15" y1="9" x2="9" y2="15" />
                  <line x1="9" y1="9" x2="15" y2="15" />
                </svg>
                <span>{t("customers.privacy.refuse").text}</span>
              </button>
            </div>

            {failed ? (
              <div className="customers-state-box customers-state-box--error" role="alert">
                <p>
                  {problemCode(failed) === MFA_REQUIRED
                    ? t("customers.limits.step_up_needed").text
                    : errorText(failed, t("customers.error.generic").text)}
                </p>
              </div>
            ) : null}
          </form>
        )}
      </div>
    </li>
  );
}

/** On the customer card: the office records a request the member made (access, correction, erasure). */
export function PrivacyRequestForm({ customerId }: { customerId: string }) {
  const t = useT();
  const api = useCustomersApi();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const [kind, setKind] = useState<Kind | "">("");
  const [notes, setNotes] = useState("");
  const record = useMutation({
    mutationFn: () => api.recordPrivacyRequest({ customerId, kind: kind as Kind, notes: notes.trim() || null }, key.current()),
    onSuccess: () => {
      key.next();
      setKind("");
      setNotes("");
      queryClient.invalidateQueries({ queryKey: ["customers", "privacy"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });
  return (
    <form
      className="customers-form customers-section customers-section-card"
      aria-labelledby="customers-privacy-record-title"
      onSubmit={(event: FormEvent) => {
        event.preventDefault();
        record.mutate();
      }}
    >
      <div className="customers-section-header">
        <div className="customers-section-title-wrap">
          <span className="customers-section-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
            </svg>
          </span>
          <h2 id="customers-privacy-record-title">{t("customers.privacy.record.title").text}</h2>
        </div>
      </div>
      <div className="customers-form-row customers-form-row--2col">
        <label className="customers-field">
          <span className="customers-field-label">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="16" x2="12" y2="12" />
              <line x1="12" y1="8" x2="12.01" y2="8" />
            </svg>
            <span>{t("customers.privacy.record.kind").text}</span>
          </span>
          <CustomSelect
            ariaLabel={t("customers.privacy.record.kind").text}
            required
            value={kind}
            onChange={(val) => setKind(val as Kind | "")}
            options={[
              { value: "", label: t("customers.status_action.choose").text },
              ...KINDS.map((each) => ({
                value: each,
                label: t(`customers.privacy.kind.${each}`).text
              }))
            ]}
          />
        </label>
        <label className="customers-field">
          <span className="customers-field-label">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
            </svg>
            <span>{t("customers.privacy.record.notes").text}</span>
          </span>
          <input
            maxLength={500}
            aria-label={t("customers.privacy.record.notes").text}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
          />
        </label>
      </div>
      <div className="customers-form-actions">
        <button type="submit" className="customers-btn-primary" disabled={record.isPending || kind === ""}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <polyline points="20 6 9 17 4 12" />
          </svg>
          <span>{t("customers.privacy.record.submit").text}</span>
        </button>
      </div>
      {record.isSuccess && (
        <p role="status">
          {t("customers.privacy.record.done").text} <Link to="/customers/privacy">{t("customers.privacy.title").text}</Link>
        </p>
      )}
      {record.isError && <p role="alert">{errorText(record.error, t("customers.error.generic").text)}</p>}
    </form>
  );
}
