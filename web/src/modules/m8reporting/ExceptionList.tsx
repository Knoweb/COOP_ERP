import { useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { ExceptionQueue, type ExceptionQueueItem } from "../../shell/components/ExceptionQueue";
import { useReportingApi, type ExceptionItem } from "./reportingApi";
import { errorText, exceptionLink } from "./reportView";

/**
 * The exception queue of the caller's scope (28A section 8), through the shell's shared
 * ExceptionQueue: each item's kind, who or what it concerns, the money at stake, since when, and
 * a link to the page where it is dealt with. The server orders it, most urgent first.
 */
export function ExceptionList() {
  const t = useT();
  const api = useReportingApi();
  const formatInstant = useFormatInstant();
  const queue = useQuery({ queryKey: ["reporting", "exceptions"], queryFn: () => api.exceptions() });

  if (queue.isLoading) {
    return <p>{t("reporting.loading").text}</p>;
  }
  if (queue.isError) {
    return <p role="alert">{errorText(queue.error, t("reporting.error.generic").text)}</p>;
  }

  const items: ExceptionQueueItem[] = (queue.data ?? []).map((item: ExceptionItem) => ({
    key: `${item.kind}-${item.subjectId}`,
    severity: item.severity === "ALERT" ? "alert" : "review",
    escalated: item.escalated,
    stateLabel: item.escalated
      ? t("reporting.exception.escalated").text
      : t(`reporting.exception.severity.${item.severity}`).text,
    what: t(`reporting.exception.kind.${item.kind}`).text,
    subject: (
      <>
        {[item.documentNumber, item.counterparty, item.subject].filter(Boolean).join(" · ")}
        {item.percent && <> ({t("reporting.exception.percent", undefined, { percent: item.percent }).text})</>}
      </>
    ),
    // Stock and an open discrepancy carry a quantity, not money (the discrepancy event has no price).
    amount: !item.amount
      ? undefined
      : item.kind === "NEGATIVE_STOCK"
        ? item.amount
        : item.kind === "DISCREPANCY_OPEN"
          ? t("reporting.exception.qty_at_issue", undefined, { qty: item.amount }).text
          : <MoneyDisplay amount={item.amount} />,
    since: formatInstant(item.since),
    href: exceptionLink(item),
    linkLabel: t("reporting.exception.view").text
  }));

  return (
    <ExceptionQueue
      caption={t("reporting.exceptions.caption").text}
      headers={{
        state: t("reporting.exceptions.col.state").text,
        what: t("reporting.exceptions.col.what").text,
        subject: t("reporting.exceptions.col.subject").text,
        amount: t("reporting.exceptions.col.amount").text,
        since: t("reporting.exceptions.col.since").text,
        action: t("reporting.exceptions.col.action").text
      }}
      items={items}
      emptyText={t("reporting.exceptions.empty").text}
    />
  );
}

/** The exception queue's own page (/reporting/exceptions). */
export function ExceptionsPage() {
  const t = useT();
  return (
    <main className="shell-page">
      <h1>{t("reporting.exceptions.title").text}</h1>
      <ExceptionList />
    </main>
  );
}
