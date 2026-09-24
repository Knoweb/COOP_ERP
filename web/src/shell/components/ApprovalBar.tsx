/**
 * The row of commands a document or register offers in its current state (doc 30 section 2.2;
 * 21A section 8: "ApprovalBar: [Activate] (disabled: reason ...) [Suspend...]").
 *
 * An action the user may not take right now is shown DISABLED WITH ITS REASON beside it, not
 * hidden: a person who sees "Activate: responsible officer not named" knows what to do next; a
 * person who sees no button at all asks the help desk. An action the user has no permission
 * for is a different case and is left out by the screen (the server refuses it anyway).
 *
 * Labels and reasons arrive already translated. The bar knows nothing about what the actions
 * do; the screen wires them, and shows the outcome (a status line, the chip changing).
 */
export type ApprovalAction = {
  id: string;
  label: string;
  onClick: () => void;
  /** Why the action is not available now; when set, the button is disabled and the reason shown. */
  disabledReason?: string;
  /** The action is under way: the button is disabled without a reason. */
  pending?: boolean;
  /** The one action that changes the document's state forward, drawn as the primary button. */
  primary?: boolean;
};

type ApprovalBarProps = {
  actions: ApprovalAction[];
};

export function ApprovalBar({ actions }: ApprovalBarProps) {
  if (actions.length === 0) {
    return null;
  }
  return (
    <div className="approval-bar" role="group">
      {actions.map((action) => {
        const reasonId = action.disabledReason ? `approval-reason-${action.id}` : undefined;
        return (
          <div key={action.id} className="approval-bar__action">
            <button
              type="button"
              className={action.primary ? "approval-bar__button approval-bar__button--primary" : "approval-bar__button"}
              onClick={action.onClick}
              disabled={Boolean(action.disabledReason) || action.pending}
              aria-describedby={reasonId}
            >
              {action.label}
            </button>
            {action.disabledReason && (
              <span id={reasonId} className="approval-bar__reason">
                {action.disabledReason}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}
