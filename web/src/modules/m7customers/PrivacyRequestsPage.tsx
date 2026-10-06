import { useState } from "react";
import type { FormEvent } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { StateChip } from "../../shell/components/StateChip";
import { useFormatInstant } from "../../shell/i18n/formats";
import { useT } from "../../shell/i18n/useT";
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
      <h1>{t("customers.privacy.title").text}</h1>
      <p>{t("customers.privacy.explain").text}</p>
      {requests.isLoading && <p>{t("customers.loading").text}</p>}
      {requests.isError && <p role="alert">{errorText(requests.error, t("customers.error.generic").text)}</p>}
      {requests.data && requests.data.length === 0 && <p>{t("customers.privacy.empty").text}</p>}
      {requests.data && requests.data.length > 0 && (
        <ul className="customers-list">
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
    <li className="customers-subsection">
      <p>
        <Link to={`/customers/${request.customerId}`}>{request.customerName}</Link> ·{" "}
        {t(`customers.privacy.kind.${request.kind}`).text} · {formatInstant(request.receivedAt)}{" "}
        <StateChip state={status} label={t(`customers.privacy.status.${request.status}`).text} />
      </p>
      {request.notes && <p>{request.notes}</p>}
      {request.outcome && <p>{request.outcome}</p>}
      {request.refusalGround && (
        <p>
          {t("customers.privacy.ground").text}: {request.refusalGround}
        </p>
      )}
      {request.kind === "ACCESS" && request.status === "FULFILLED" && canAnswer && (
        <button type="button" onClick={() => void download()}>
          {t("customers.privacy.download").text}
        </button>
      )}
      {canAnswer && request.status === "RECEIVED" && (
        <form
          className="customers-form"
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            fulfil.mutate();
          }}
        >
          {request.kind === "ERASURE" && <p role="note">{t("customers.privacy.erasure_warning").text}</p>}
          <label className="customers-field">
            {t("customers.privacy.answer").text}
            <input maxLength={500} value={text} onChange={(e) => setText(e.target.value)} />
          </label>
          <div className="customers-filter-bar">
            <button type="submit" disabled={fulfil.isPending || (request.kind === "CORRECTION" && text.trim() === "")}>
              {t("customers.privacy.fulfil").text}
            </button>
            <button type="button" disabled={refuse.isPending || text.trim() === ""} onClick={() => refuse.mutate()}>
              {t("customers.privacy.refuse").text}
            </button>
          </div>
          {failed ? (
            <p role="alert">
              {problemCode(failed) === MFA_REQUIRED
                ? t("customers.limits.step_up_needed").text
                : errorText(failed, t("customers.error.generic").text)}
            </p>
          ) : null}
        </form>
      )}
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
      className="customers-form customers-section"
      aria-labelledby="customers-privacy-record-title"
      onSubmit={(event: FormEvent) => {
        event.preventDefault();
        record.mutate();
      }}
    >
      <h2 id="customers-privacy-record-title">{t("customers.privacy.record.title").text}</h2>
      <label className="customers-field">
        {t("customers.privacy.record.kind").text}
        <select
          aria-label={t("customers.privacy.record.kind").text}
          value={kind}
          required
          onChange={(e) => setKind(e.target.value as Kind | "")}
        >
          <option value="">{t("customers.status_action.choose").text}</option>
          {KINDS.map((each) => (
            <option key={each} value={each}>
              {t(`customers.privacy.kind.${each}`).text}
            </option>
          ))}
        </select>
      </label>
      <label className="customers-field">
        {t("customers.privacy.record.notes").text}
        <input maxLength={500} value={notes} onChange={(e) => setNotes(e.target.value)} />
      </label>
      <button type="submit" disabled={record.isPending || kind === ""}>
        {t("customers.privacy.record.submit").text}
      </button>
      {record.isSuccess && (
        <p role="status">
          {t("customers.privacy.record.done").text} <Link to="/customers/privacy">{t("customers.privacy.title").text}</Link>
        </p>
      )}
      {record.isError && <p role="alert">{errorText(record.error, t("customers.error.generic").text)}</p>}
    </form>
  );
}
