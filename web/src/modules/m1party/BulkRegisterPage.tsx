import { useState } from "react";
import type { FormEvent } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { usePartyApi } from "./partyApi";
import type { BulkValidationReport } from "./partyApi";
import { errorText } from "./SocietyRegisterPage";

/**
 * Registering many societies from one CSV (21A section 8, BulkRegisterEntities): the file
 * goes up, the server validates every row and registers all of them or none, and answers
 * with a validation report. The report is shown as a table, one row per line of the file,
 * with the problems of a rejected row named by field: a clerk fixes the file and uploads it
 * again. The problem codes are the server's message ids; their words live in this module's
 * catalogue under party.bulk.problem, so the screen never shows a bare code.
 */
interface ColumnFormat {
  key: string;
  name: string;
  required: boolean;
}

const TEMPLATE_COLUMNS: ColumnFormat[] = [
  {
    key: "entity_code",
    name: "Entity Code",
    required: true
  },
  {
    key: "entity_type",
    name: "Entity Type",
    required: true
  },
  {
    key: "legal_name_en",
    name: "Legal Name English",
    required: true
  },
  {
    key: "legal_name_si",
    name: "Legal Name Sinhala",
    required: false
  },
  {
    key: "legal_name_ta",
    name: "Legal Name Tamil",
    required: false
  },
  {
    key: "registration_no",
    name: "Registration Number",
    required: false
  },
  {
    key: "vat_registration_no",
    name: "VAT Registration Number",
    required: false
  },
  {
    key: "district",
    name: "District",
    required: false
  },
  {
    key: "default_language",
    name: "Default Language",
    required: false
  },
  {
    key: "financial_year_start_month",
    name: "Financial Year Start Month",
    required: false
  }
];

export function BulkRegisterPage() {
  const t = useT();
  const queryClient = useQueryClient();
  const api = usePartyApi();
  const idempotencyKey = useIdempotencyKey();
  const canRegister = useHasPermission("gov.entity.register");

  const [file, setFile] = useState<File | null>(null);

  const upload = useMutation({
    mutationFn: () => api.bulkRegister(file!, idempotencyKey.current()),
    onSuccess: (report) => {
      idempotencyKey.next();
      if (report.status === "REGISTERED") {
        queryClient.invalidateQueries({ queryKey: ["party", "societies"] });
      }
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        idempotencyKey.next();
      }
    }
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (file) {
      upload.mutate();
    }
  };

  if (!canRegister) {
    return (
      <main className="shell-page">
        <div className="bulk-upload-container">
          <div className="bulk-upload-card">
            <h1 className="bulk-title">{t("party.bulk.title").text}</h1>
            <p role="note">{t("party.new.not_allowed").text}</p>
          </div>
        </div>
      </main>
    );
  }

  return (
    <main className="shell-page">
      <div className="bulk-upload-container">
        <div className="bulk-upload-card">
          <div>
            <Link className="back-pill-btn" to="/party/societies">
              <svg
                viewBox="0 0 24 24"
                width="16"
                height="16"
                stroke="currentColor"
                strokeWidth="2"
                fill="none"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <path d="m12 19-7-7 7-7" />
                <path d="M19 12H5" />
              </svg>
              {t("party.back_to_register").text}
            </Link>
          </div>

          <h1 className="bulk-title">{t("party.bulk.title").text}</h1>
          <p className="bulk-intro">{t("party.bulk.intro").text}</p>

          <div className="bulk-excel-card">
            <div className="bulk-excel-header">
              <div className="bulk-excel-title-group">
                <div className="bulk-excel-icon-box" aria-hidden="true">
                  <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                    <polyline points="14 2 14 8 20 8" />
                    <line x1="8" y1="13" x2="16" y2="13" />
                    <line x1="8" y1="17" x2="16" y2="17" />
                  </svg>
                </div>
                <div>
                  <h2 className="bulk-excel-title">{t("party.bulk.template.title").text}</h2>
                  <p className="bulk-excel-subtitle">{t("party.bulk.template.desc").text}</p>
                </div>
              </div>
            </div>

            <div className="bulk-excel-table-scroll">
              <table className="bulk-excel-table">
                <thead>
                  <tr className="bulk-excel-row-alpha">
                    <th className="bulk-excel-corner-cell">#</th>
                    {TEMPLATE_COLUMNS.map((_, i) => (
                      <th key={i} className="bulk-excel-col-letter">
                        {String.fromCharCode(65 + i)}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  <tr className="bulk-excel-row-header">
                    <td className="bulk-excel-row-index">1</td>
                    {TEMPLATE_COLUMNS.map((col) => (
                      <td key={col.key} className="bulk-excel-header-cell">
                        <div className="bulk-excel-header-content">
                          <span className="bulk-col-name">{col.name}</span>
                          {col.required ? (
                            <span className="bulk-tag-req">{t("party.bulk.required").text}</span>
                          ) : (
                            <span className="bulk-tag-opt">{t("party.bulk.optional").text}</span>
                          )}
                        </div>
                      </td>
                    ))}
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <form onSubmit={submit} className="bulk-upload-form">
            <div className="bulk-upload-section">
              <div className="bulk-file-field">
                <label className="bulk-file-label" htmlFor="bulk-file-input">
                  {t("party.bulk.file").text}
                </label>
                <input
                  id="bulk-file-input"
                  type="file"
                  accept=".csv,text/csv"
                  className="bulk-file-input"
                  onChange={(event) => setFile(event.target.files?.[0] ?? null)}
                />
              </div>

              <button
                type="submit"
                className="bulk-submit-btn"
                disabled={!file || upload.isPending}
              >
                {upload.isPending ? (
                  <>
                    <svg className="spin-icon" viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2.5" fill="none" strokeLinecap="round" strokeLinejoin="round">
                      <line x1="12" y1="2" x2="12" y2="6"></line>
                      <line x1="12" y1="18" x2="12" y2="22"></line>
                      <line x1="4.93" y1="4.93" x2="7.76" y2="7.76"></line>
                      <line x1="16.24" y1="16.24" x2="19.07" y2="19.07"></line>
                      <line x1="2" y1="12" x2="6" y2="12"></line>
                      <line x1="18" y1="12" x2="22" y2="12"></line>
                      <line x1="4.93" y1="19.07" x2="7.76" y2="16.24"></line>
                      <line x1="16.24" y1="7.76" x2="19.07" y2="4.93"></line>
                    </svg>
                    {t("party.bulk.uploading").text}
                  </>
                ) : (
                  <>
                    <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                      <polyline points="17 8 12 3 7 8"></polyline>
                      <line x1="12" y1="3" x2="12" y2="15"></line>
                    </svg>
                    {t("party.bulk.submit").text}
                  </>
                )}
              </button>
            </div>

            {upload.isError && (
              <div className="register-error-banner" role="alert">
                <svg viewBox="0 0 24 24" width="18" height="18" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="10"></circle>
                  <line x1="12" y1="8" x2="12" y2="12"></line>
                  <line x1="12" y1="16" x2="12.01" y2="16"></line>
                </svg>
                {errorText(upload.error, t("party.error.generic").text)}
              </div>
            )}
          </form>
        </div>

        {upload.data && <Report report={upload.data} />}
      </div>
    </main>
  );
}

function Report({ report }: { report: BulkValidationReport }) {
  const t = useT();
  const registered = report.status === "REGISTERED";
  return (
    <section aria-live="polite" className="bulk-report-card">
      <div className={registered ? "bulk-banner-success" : "bulk-banner-error"} role="status">
        {registered ? (
          <svg viewBox="0 0 24 24" width="20" height="20" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
            <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path>
            <polyline points="22 4 12 14.01 9 11.01"></polyline>
          </svg>
        ) : (
          <svg viewBox="0 0 24 24" width="20" height="20" stroke="currentColor" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="10"></circle>
            <line x1="12" y1="8" x2="12" y2="12"></line>
            <line x1="12" y1="16" x2="12.01" y2="16"></line>
          </svg>
        )}
        <span>
          {registered
            ? t("party.bulk.registered", undefined, { count: report.registered }).text
            : t("party.bulk.rejected", undefined, { rejected: report.rejected, rows: report.rows }).text}
        </span>
      </div>

      <div className="data-table-wrap">
        <table className="modern-table">
          <thead>
            <tr>
              <th scope="col">{t("party.bulk.col.line").text}</th>
              <th scope="col">{t("party.col.code").text}</th>
              <th scope="col">{t("party.bulk.col.result").text}</th>
              <th scope="col">{t("party.bulk.col.problems").text}</th>
            </tr>
          </thead>
          <tbody>
            {report.results.map((row) => (
              <tr key={row.line}>
                <td className="numeric-cell">{row.line}</td>
                <td>
                  {row.entityId ? (
                    <Link className="entity-link" to={`/party/societies/${row.entityId}`}>
                      {row.entityCode}
                    </Link>
                  ) : (
                    <strong>{row.entityCode}</strong>
                  )}
                </td>
                <td>
                  <span className={`bulk-status-badge ${row.status === "OK" ? "bulk-status-badge--ok" : "bulk-status-badge--error"}`}>
                    {row.status === "OK" ? (
                      <svg viewBox="0 0 24 24" width="12" height="12" stroke="currentColor" strokeWidth="3" fill="none" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="20 6 9 17 4 12"></polyline>
                      </svg>
                    ) : (
                      <svg viewBox="0 0 24 24" width="12" height="12" stroke="currentColor" strokeWidth="3" fill="none" strokeLinecap="round" strokeLinejoin="round">
                        <line x1="18" y1="6" x2="6" y2="18"></line>
                        <line x1="6" y1="6" x2="18" y2="18"></line>
                      </svg>
                    )}
                    {t(row.status === "OK" ? "party.bulk.row.ok" : "party.bulk.row.error").text}
                  </span>
                </td>
                <td>
                  {row.problems.length > 0 && (
                    <ul className="party-error-list">
                      {row.problems.map((problem) => (
                        <li key={`${problem.field}:${problem.code}`}>
                          <strong>{problem.field}</strong>: {t(`party.bulk.problem.${problem.code}`).text}
                        </li>
                      ))}
                    </ul>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
