import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { StateChip } from "./StateChip";

/** One item of a queue, every text ALREADY TRANSLATED by the caller. */
export type ExceptionQueueItem = {
  /** A stable key for the row. */
  key: string;
  /** alert: needs attention now; review: somebody should look. */
  severity: "alert" | "review";
  /** The word on the chip ("Alert", "Review", or "Escalated"). */
  stateLabel: string;
  /** Escalated items show the alert look whatever their severity. */
  escalated?: boolean;
  /** What happened: "Bounced cheque". */
  what: string;
  /** With whom or where: the counterparty, the item and the shop. */
  subject?: ReactNode;
  /** The money or quantity at stake, already formatted (MoneyDisplay is fine). */
  amount?: ReactNode;
  /** When it arose, already formatted. */
  since: string;
  /** Where the user acts on it, and the link's text. */
  href?: string;
  linkLabel?: string;
};

type ExceptionQueueProps = {
  /** The table's caption: what the queue is. */
  caption: string;
  /** The column headers, translated: state, what, subject, amount, since, action. */
  headers: { state: string; what: string; subject: string; amount: string; since: string; action: string };
  items: ExceptionQueueItem[];
  /** Shown instead of the table when the queue is empty. */
  emptyText: string;
};

/**
 * The shared exception queue (doc 30; 28A section 8, "Exception queue"): the things that need a
 * person, one row each, in the order the server gave them (most urgent first). Each row carries
 * its state as a StateChip, so the urgency is a word and a symbol, never a colour alone. The
 * component translates nothing and fetches nothing: the module owns its words and its data.
 */
export function ExceptionQueue({ caption, headers, items, emptyText }: ExceptionQueueProps) {
  if (items.length === 0) {
    return <p>{emptyText}</p>;
  }
  return (
    <div className="modern-table-card">
      <div className="modern-table-scroll">
        <table className="modern-table">
          <caption>{caption}</caption>
          <thead>
            <tr>
              <th scope="col">{headers.state}</th>
              <th scope="col">{headers.what}</th>
              <th scope="col">{headers.subject}</th>
              <th scope="col" style={{ textAlign: "right" }}>
                {headers.amount}
              </th>
              <th scope="col">{headers.since}</th>
              <th scope="col">{headers.action}</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.key}>
                <td>
                  <StateChip
                    state={item.escalated || item.severity === "alert" ? "alert" : "disputed"}
                    label={item.stateLabel}
                  />
                </td>
                <td>{item.what}</td>
                <td>{item.subject}</td>
                <td style={{ textAlign: "right" }}>{item.amount}</td>
                <td>{item.since}</td>
                <td>{item.href && item.linkLabel ? <Link to={item.href}>{item.linkLabel}</Link> : null}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
