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
        <h1>{t("party.bulk.title").text}</h1>
        <p role="note">{t("party.new.not_allowed").text}</p>
      </main>
    );
  }

  return (
    <main className="shell-page">
      <p>
        <Link to="/party/societies">{t("party.back_to_register").text}</Link>
      </p>
      <h1>{t("party.bulk.title").text}</h1>
      <p>{t("party.bulk.intro").text}</p>
      <p>
        <code>{t("party.bulk.columns").text}</code>
      </p>

      <form onSubmit={submit} style={{ display: "grid", gap: "var(--target-gap)", marginBottom: "var(--space-3)" }}>
        <label style={{ display: "grid", gap: "var(--space-half)" }}>
          {t("party.bulk.file").text}
          <input type="file" accept=".csv,text/csv" onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
        </label>
        <button type="submit" disabled={!file || upload.isPending}>
          {upload.isPending ? t("party.bulk.uploading").text : t("party.bulk.submit").text}
        </button>
        {upload.isError && <p role="alert">{errorText(upload.error, t("party.error.generic").text)}</p>}
      </form>

      {upload.data && <Report report={upload.data} />}
    </main>
  );
}

function Report({ report }: { report: BulkValidationReport }) {
  const t = useT();
  const registered = report.status === "REGISTERED";
  return (
    <section aria-live="polite">
      <p role="status">
        {registered
          ? t("party.bulk.registered", undefined, { count: report.registered }).text
          : t("party.bulk.rejected", undefined, { rejected: report.rejected, rows: report.rows }).text}
      </p>
      <table style={{ width: "100%", borderCollapse: "collapse" }}>
        <thead>
          <tr>
            <th scope="col" style={cell}>{t("party.bulk.col.line").text}</th>
            <th scope="col" style={cell}>{t("party.col.code").text}</th>
            <th scope="col" style={cell}>{t("party.bulk.col.result").text}</th>
            <th scope="col" style={cell}>{t("party.bulk.col.problems").text}</th>
          </tr>
        </thead>
        <tbody>
          {report.results.map((row) => (
            <tr key={row.line}>
              <td style={cell}>{row.line}</td>
              <td style={cell}>
                {row.entityId ? <Link to={`/party/societies/${row.entityId}`}>{row.entityCode}</Link> : row.entityCode}
              </td>
              <td style={cell}>{t(row.status === "OK" ? "party.bulk.row.ok" : "party.bulk.row.error").text}</td>
              <td style={cell}>
                {row.problems.length > 0 && (
                  <ul style={{ margin: 0, paddingLeft: "var(--space-2)" }}>
                    {row.problems.map((problem) => (
                      <li key={`${problem.field}:${problem.code}`}>
                        {problem.field}: {t(`party.bulk.problem.${problem.code}`).text}
                      </li>
                    ))}
                  </ul>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

const cell = { textAlign: "left" as const, padding: "var(--space-1)", borderBottom: "var(--border-width) solid var(--color-border)", verticalAlign: "top" as const };
