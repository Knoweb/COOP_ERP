import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { ApiProblem, useApiClient } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { isOpenableFileUrl } from "../../shell/api/openServerFile";
import { hasPermission } from "../../shell/auth/permissions";
import { useSession } from "../../shell/auth/session";
import type { paths as sessionPaths } from "../../generated/session";
import { ApprovalBar, type ApprovalAction } from "../../shell/components/ApprovalBar";
import { AttachmentCapture } from "../../shell/components/AttachmentCapture";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { PageHeader } from "../../shell/components/PageHeader";
import { ReasonCapture } from "../../shell/components/ReasonCapture";
import { StateChip } from "../../shell/components/StateChip";
import { useInventoryApi, type WriteOff } from "./inventoryApi";
import { SkuLabel } from "./SkuLabel";
import { nextWriteOffStep, photosMissing, writeOffChip } from "./stockControl";
import { errorText, isSyntheticBatchNo } from "./stockView";
import "./inventory.css";

function WriteOffPhoto({
  writeOffId,
  locationId,
  photo,
  number
}: {
  writeOffId: string;
  locationId: string;
  photo: WriteOff["photos"][number];
  number: number;
}) {
  const t = useT();
  const api = useInventoryApi(locationId);
  const [previewing, setPreviewing] = useState(false);
  const view = useQuery({
    queryKey: ["inventory", "write-off", writeOffId, "photo", photo.attachmentId],
    enabled: photo.status === "COMPLETE",
    queryFn: () => api.writeOffPhoto(writeOffId, photo.attachmentId)
  });
  const url = view.data && isOpenableFileUrl(view.data) ? view.data : undefined;
  const label = t("inventory.writeoff.photo.alt", undefined, { number }).text;

  return (
    <li className="writeoff-photo">
      {photo.status === "COMPLETE" && view.isLoading && <span>{t("inventory.writeoff.photo.loading").text}</span>}
      {photo.status === "COMPLETE" && (view.isError || (view.data && !url)) && (
        <span role="alert">{t("inventory.writeoff.photo.unavailable").text}</span>
      )}
      {url && (
        <button
          type="button"
          className="writeoff-photo__thumbnail"
          aria-label={t("inventory.writeoff.photo.view", undefined, { number }).text}
          onClick={() => setPreviewing(true)}
        >
          <img src={url} alt={label} />
        </button>
      )}
      <span>{`${number}. ${t(`inventory.writeoff.photo.status.${photo.status}`).text}`}</span>
      {previewing && url && (
        <div
          className="writeoff-photo-preview"
          role="dialog"
          aria-modal="true"
          aria-label={t("inventory.writeoff.photo.preview", undefined, { number }).text}
          onKeyDown={(event) => {
            if (event.key === "Escape") setPreviewing(false);
          }}
        >
          <div className="writeoff-photo-preview__panel">
            <img src={url} alt={label} />
            <button type="button" autoFocus onClick={() => setPreviewing(false)}>
              {t("inventory.writeoff.photo.close").text}
            </button>
          </div>
        </div>
      )}
    </li>
  );
}

/**
 * One write-off (25A section 8, "Damage/expiry register" and its approval): the category, the
 * lines, the photographs (required when the category or a single-staff location says so), who
 * must act next, and the bar of what the reader may do now: submit the draft, witness it (another
 * person than the requester), approve it within the band, or reject it with a reason.
 */
export function WriteOffPage() {
  const { writeOffId = "" } = useParams();
  const t = useT();
  const api = useInventoryApi();
  const queryClient = useQueryClient();
  const session = useSession();
  const formatInstant = useFormatInstant();
  const stepKey = useIdempotencyKey();
  const photoKey = useIdempotencyKey();
  const rejectKey = useIdempotencyKey();
  const [rejecting, setRejecting] = useState(false);

  const writeOff = useQuery({
    queryKey: ["inventory", "write-off", writeOffId],
    queryFn: () => api.writeOff(writeOffId)
  });
  // An entity-wide user may hold the approval role only at this write-off's location. Resolve
  // action visibility there, and send every mutation in that same scope; the list/detail read
  // above remains in the shell scope so an authorised entity-wide reader can open the document.
  const actionLocationId = writeOff.data?.locationId ?? null;
  const scopedApi = useInventoryApi(actionLocationId);
  const scopedSessionApi = useApiClient<sessionPaths>({ locationId: actionLocationId });
  const scopedSession = useQuery({
    queryKey: ["session", "location", session?.userId, actionLocationId],
    enabled: actionLocationId !== null,
    queryFn: async () => {
      const { data } = await scopedSessionApi.GET("/v1/session");
      if (!data) {
        throw new Error("GET /v1/session answered without a body");
      }
      return data;
    }
  });
  const canRequest = scopedSession.data ? hasPermission(scopedSession.data, "inv.writeoff.request") : false;
  const canWitness = scopedSession.data ? hasPermission(scopedSession.data, "inv.writeoff.witness") : false;
  const canApprove = scopedSession.data ? hasPermission(scopedSession.data, "inv.writeoff.approve") : false;

  const refresh = () => queryClient.invalidateQueries({ queryKey: ["inventory"] });
  const forget = (key: { next: () => void }) => (error: unknown) => {
    if (error instanceof ApiProblem) {
      key.next();
    }
  };
  const step = useMutation({
    mutationFn: (next: "submit" | "witness" | "approve") =>
      scopedApi.writeOffStep(writeOffId, next, stepKey.current()),
    onSuccess: () => {
      stepKey.next();
      refresh();
    },
    onError: forget(stepKey)
  });
  const photo = useMutation({
    mutationFn: (file: File) => scopedApi.addWriteOffPhoto(writeOffId, file, photoKey.current()),
    onSuccess: () => {
      photoKey.next();
      refresh();
    },
    onError: forget(photoKey)
  });
  const reject = useMutation({
    mutationFn: (reason: string) => scopedApi.rejectWriteOff(writeOffId, reason, rejectKey.current()),
    onSuccess: () => {
      rejectKey.next();
      setRejecting(false);
      refresh();
    },
    onError: forget(rejectKey)
  });

  if (writeOff.isLoading) {
    return <main className="shell-page">{t("inventory.loading").text}</main>;
  }
  if (writeOff.isError || !writeOff.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(writeOff.error, t("inventory.error.generic").text)}</p>
      </main>
    );
  }

  const w = writeOff.data;
  const next = nextWriteOffStep(w.status);
  const mine = session?.userId !== undefined && session.userId === w.requestedBy;
  const actions: ApprovalAction[] = [];
  if (next === "submit" && canRequest) {
    actions.push({
      id: "submit",
      label: t("inventory.writeoff.submit").text,
      primary: true,
      pending: step.isPending,
      disabledReason: photosMissing(w) ? t("inventory.writeoff.photos_missing").text : undefined,
      onClick: () => step.mutate("submit")
    });
  }
  if (next === "witness" && canWitness) {
    actions.push({
      id: "witness",
      label: t("inventory.writeoff.witness").text,
      primary: true,
      pending: step.isPending,
      disabledReason: mine ? t("inventory.writeoff.requester_cannot").text : undefined,
      onClick: () => step.mutate("witness")
    });
  }
  if (next === "approve" && canApprove) {
    actions.push({
      id: "approve",
      label: t("inventory.writeoff.approve").text,
      primary: true,
      pending: step.isPending,
      disabledReason: mine ? t("inventory.writeoff.requester_cannot").text : undefined,
      onClick: () => step.mutate("approve")
    });
  }
  if ((w.status === "REQUESTED" || w.status === "WITNESSED") && canApprove) {
    actions.push({
      id: "reject",
      label: t("inventory.writeoff.reject").text,
      pending: reject.isPending,
      onClick: () => setRejecting(true)
    });
  }

  return (
    <main className="shell-page">
      <div>
        <Link className="back-pill-btn" to="/inventory/write-offs">
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
          {t("inventory.writeoff.back").text}
        </Link>
      </div>
      <PageHeader icon="writeoffs" title={t("inventory.writeoff.doc_title").text} />
      <p>
        <StateChip state={writeOffChip(w.status)} label={t(`inventory.writeoff.status.${w.status}`).text} />
        {w.documentNo && ` ${t("inventory.writeoff.document").text}: ${w.documentNo}`}
      </p>
      <p>{`${t("inventory.writeoff.category").text}: ${t(`inventory.writeoff.category.${w.category}`).text}`}</p>
      {w.note && <p>{w.note}</p>}
      {w.value != null && (
        <p>
          {`${t("inventory.writeoff.value").text}: `}
          <MoneyDisplay amount={w.value} />
          {w.band != null && ` · ${t("inventory.count.band", undefined, { band: w.band }).text}`}
        </p>
      )}
      {w.remoteWitness && <p>{t("inventory.writeoff.remote").text}</p>}
      {w.witnessedAt && <p>{`${t("inventory.writeoff.witness").text}: ${formatInstant(w.witnessedAt)}`}</p>}
      {w.rejectReason && <p>{w.rejectReason}</p>}

      <table>
        <thead>
          <tr>
            <th>{t("inventory.column.item").text}</th>
            <th>{t("inventory.column.batch").text}</th>
            <th>{t("inventory.column.condition").text}</th>
            <th>{t("inventory.column.qty").text}</th>
          </tr>
        </thead>
        <tbody>
          {w.lines.map((line) => (
            <tr key={line.lineNo}>
              <td>
                <Link to={`/inventory/locations/${w.locationId}/skus/${line.skuId}`}>
                  <SkuLabel skuId={line.skuId} />
                </Link>
              </td>
              <td>{isSyntheticBatchNo(line.batchNo) ? t("inventory.field.batch_not_tracked").text : (line.batchNo ?? "")}</td>
              <td>{t(`inventory.condition.${line.condition}`).text}</td>
              <td className="numeric-cell">{line.qty}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>{t("inventory.writeoff.photos").text}</h2>
      {w.photos.length > 0 && (
        <ol className="writeoff-photo-list">
          {w.photos.map((item, index) => (
            <WriteOffPhoto
              key={item.attachmentId}
              writeOffId={writeOffId}
              locationId={w.locationId}
              photo={item}
              number={index + 1}
            />
          ))}
        </ol>
      )}
      <AttachmentCapture
        attachments={w.photos.map((p, index) => ({
          id: p.attachmentId,
          label: `${index + 1}. ${t(`inventory.writeoff.photo.status.${p.status}`).text}`
        }))}
        addLabel={t("inventory.writeoff.photo.add").text}
        emptyLabel={t("inventory.writeoff.photo.none").text}
        requiredLabel={w.photosRequired ? t("inventory.writeoff.photos_required").text : undefined}
        disabled={w.status !== "DRAFT" || !canRequest || photo.isPending}
        showList={w.photos.length === 0}
        onAdd={(file) => photo.mutate(file)}
      />

      {next && <p>{t(`inventory.writeoff.next.${next}`).text}</p>}
      <ApprovalBar actions={actions} />
      {rejecting && (
        <ReasonCapture
          title={t("inventory.writeoff.reject.question").text}
          codes={["NOT_LOST", "WRONG_QTY", "OTHER"].map((code) => ({
            code,
            label: t(`inventory.writeoff.reason.${code}`).text
          }))}
          pending={reject.isPending}
          onCancel={() => setRejecting(false)}
          onConfirm={(code, text) =>
            reject.mutate([t(`inventory.writeoff.reason.${code}`).text, text].filter(Boolean).join(": "))
          }
        />
      )}
      {[step, photo, reject].map((m, i) =>
        m.isError ? (
          <p key={i} role="alert">
            {errorText(m.error, t("inventory.error.generic").text)}
          </p>
        ) : null
      )}
    </main>
  );
}
