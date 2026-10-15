import { useState } from "react";
import type { FormEvent } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { usePartyApi } from "./partyApi";
import type { BulkNetworkReport, BulkRegisterNetworkRequest, BulkShopItem, BulkUserItem, BulkDeviceItem } from "./partyApi";
import { errorText } from "./SocietyRegisterPage";

/**
 * Bulk registration of an entity's retail network (CAT-08): shops, till positions, users and devices.
 * Accepts structured JSON or unified CSV spreadsheet. Validates all-or-none in one transaction.
 */
export function BulkNetworkRegisterPage() {
  const t = useT();
  const queryClient = useQueryClient();
  const api = usePartyApi();
  const idempotencyKey = useIdempotencyKey();
  const canRegister = useHasPermission("prt.location.register");

  const [file, setFile] = useState<File | null>(null);
  const [parseError, setParseError] = useState<string | null>(null);

  const upload = useMutation({
    mutationFn: async () => {
      if (!file) throw new Error("No file selected");
      setParseError(null);
      const content = await file.text();
      let request: BulkRegisterNetworkRequest;

      if (file.name.endsWith(".json")) {
        try {
          request = JSON.parse(content) as BulkRegisterNetworkRequest;
        } catch {
          throw new Error("Invalid JSON format in uploaded file");
        }
      } else {
        request = parseCsvToRequest(content);
      }

      return api.bulkRegisterNetwork(request, idempotencyKey.current());
    },
    onSuccess: (report) => {
      idempotencyKey.next();
      if (report.status === "REGISTERED") {
        queryClient.invalidateQueries({ queryKey: ["party"] });
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
        <h1>{t("party.bulk_network.title").text}</h1>
        <p role="note">{t("party.bulk_network.not_allowed").text}</p>
      </main>
    );
  }

  return (
    <main className="shell-page">
      <p>
        <Link className="back-link" to="/party/societies">
          <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
          {t("party.back_to_register").text}
        </Link>
      </p>
      <h1>{t("party.bulk_network.title").text}</h1>
      <p>{t("party.bulk_network.intro").text}</p>

      <form onSubmit={submit} className="party-form-row-margin">
        <label className="party-form-field">
          {t("party.bulk_network.file").text}
          <input
            type="file"
            accept=".csv,text/csv,.json,application/json"
            onChange={(event) => {
              setFile(event.target.files?.[0] ?? null);
              setParseError(null);
            }}
          />
        </label>
        <button type="submit" disabled={!file || upload.isPending}>
          {upload.isPending ? t("party.bulk_network.uploading").text : t("party.bulk_network.submit").text}
        </button>
        {parseError && <p role="alert">{parseError}</p>}
        {upload.isError && <p role="alert">{errorText(upload.error, t("party.error.generic").text)}</p>}
      </form>

      {upload.data && <NetworkReportView report={upload.data} />}
    </main>
  );
}

function parseCsvToRequest(csvText: string): BulkRegisterNetworkRequest {
  const lines = csvText.split(/\r?\n/).filter((l) => l.trim().length > 0);
  if (lines.length < 2) {
    return { shops: [], users: [], devices: [] };
  }

  const headers = lines[0].split(",").map((h) => h.trim().toLowerCase());
  const shopsMap = new Map<string, BulkShopItem>();
  const usersMap = new Map<string, BulkUserItem>();
  const devices: BulkDeviceItem[] = [];

  for (let i = 1; i < lines.length; i++) {
    const values = lines[i].split(",").map((v) => v.trim());
    const row: Record<string, string> = {};
    headers.forEach((h, idx) => {
      row[h] = values[idx] ?? "";
    });

    const shopCode = row["shop_code"] || row["location_code"] || row["code"] || "";
    const nameEn = row["name_en"] || row["shop_name_en"] || row["name"] || "";
    const nameSi = row["name_si"] || row["shop_name_si"] || undefined;
    const nameTa = row["name_ta"] || row["shop_name_ta"] || undefined;
    const address = row["address"] || undefined;
    const district = row["district"] || undefined;
    const lang = (row["language"] || row["default_language"]) as "en" | "si" | "ta" | undefined;
    const sizeBand = row["size_band"] as "S" | "M" | "L" | undefined;
    const posStr = row["position_no"] || row["position"] || row["lane"] || "";

    if (shopCode) {
      if (!shopsMap.has(shopCode)) {
        shopsMap.set(shopCode, {
          locationCode: shopCode,
          nameEn: nameEn || shopCode,
          nameSi,
          nameTa,
          address,
          district,
          language: lang,
          sizeBand,
          tillPositions: []
        });
      }
      if (posStr) {
        const pos = parseInt(posStr, 10);
        if (!isNaN(pos)) {
          const shop = shopsMap.get(shopCode)!;
          if (!shop.tillPositions?.includes(pos)) {
            shop.tillPositions = [...(shop.tillPositions ?? []), pos];
          }
        }
      }
    }

    const username = row["username"] || row["user_name"] || "";
    const displayName = row["display_name"] || row["full_name"] || username;
    const userKind = (row["user_kind"] || "TILL") as "BACK_OFFICE" | "TILL" | "BOTH";
    const pin = row["pin"] || undefined;
    if (username && !usersMap.has(username.toLowerCase())) {
      usersMap.set(username.toLowerCase(), {
        username,
        displayName,
        language: lang,
        userKind,
        pin,
        locationCode: shopCode || undefined
      });
    }

    const serial = row["hardware_serial"] || row["serial"] || "";
    const deviceKind = (row["device_kind"] || "POS_TERMINAL") as "POS_TERMINAL" | "WORKSTATION" | "DRIVER_MOBILE";
    const appVersion = row["app_version"] || "1.0.0";
    if (serial) {
      devices.push({
        hardwareSerial: serial,
        deviceKind,
        locationCode: shopCode,
        positionNo: posStr ? parseInt(posStr, 10) : undefined,
        appVersion
      });
    }
  }

  return {
    shops: Array.from(shopsMap.values()),
    users: Array.from(usersMap.values()),
    devices
  };
}

function NetworkReportView({ report }: { report: BulkNetworkReport }) {
  const t = useT();
  const registered = report.status === "REGISTERED";

  return (
    <section aria-live="polite">
      <p role="status">
        {registered
          ? t("party.bulk_network.registered", undefined, {
              shops: report.shopsRegistered,
              positions: report.positionsRegistered,
              users: report.usersRegistered,
              devices: report.devicesRegistered
            }).text
          : t("party.bulk_network.rejected", undefined, { count: report.problems.length }).text}
      </p>

      {report.problems.length > 0 && (
        <table className="party-table-full">
          <thead>
            <tr>
              <th scope="col" style={cell}>{t("party.bulk_network.col.category").text}</th>
              <th scope="col" style={cell}>{t("party.bulk_network.col.identifier").text}</th>
              <th scope="col" style={cell}>{t("party.bulk_network.col.field").text}</th>
              <th scope="col" style={cell}>{t("party.bulk_network.col.problem").text}</th>
            </tr>
          </thead>
          <tbody>
            {report.problems.map((problem, idx) => (
              <tr key={`${problem.category}:${problem.identifier ?? ""}:${problem.field}:${idx}`}>
                <td style={cell}>{problem.category}</td>
                <td style={cell}>{problem.identifier ?? "—"}</td>
                <td style={cell}>{problem.field}</td>
                <td style={cell}>
                  {t(problem.code).text !== problem.code ? t(problem.code).text : problem.code}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

const cell = {
  textAlign: "left" as const,
  padding: "var(--space-1)",
  borderBottom: "var(--border-width) solid var(--color-border)",
  verticalAlign: "top" as const
};
