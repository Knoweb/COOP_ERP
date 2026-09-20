import { useT } from "../i18n/useT";

/**
 * The band across the top of every page while the shell is in training (sandbox) mode
 * (doc 30 sections 2.2 and 2.3, T-04: "training mode is loud"). A document made for practice must
 * never be mistaken for a real one, so the band cannot be closed, says in a full sentence what
 * training mode means, and uses a colour that nothing else uses. Outside training mode it
 * renders nothing at all.
 *
 * It is a labelled region, like the scope banner, so a screen-reader user finds it among the
 * landmarks of every page. It holds no control and takes no focus.
 *
 * WHERE `active` COMES FROM. There is no training mode in the backend yet. The shell passes
 * isTrainingMode() (below), which reads a build flag, so that the banner can be seen and tested.
 * The real source is a later concern of the kernel and M6 (the sandbox of T-04: probably a
 * claim of the session or a property of the deployment). When it exists, only isTrainingMode
 * changes; this component and its callers stay as they are.
 */
export function TrainingBadge({ active }: { active: boolean }) {
  const t = useT();

  if (!active) {
    return null;
  }
  return (
    <div className="training-badge" role="region" aria-label={t("shell.training.title").text}>
      {/* The mark is decoration; the words beside it say everything. */}
      <span aria-hidden="true">▲</span>
      <strong className="training-badge__title">{t("shell.training.title").text}</strong>
      <span>{t("shell.training.text").text}</span>
    </div>
  );
}

/**
 * TEMPORARY SOURCE, see above. To look at training mode on a development machine, put the line
 * VITE_TRAINING_MODE=true into web/.env.local (the file is ignored by git); Vite restarts by
 * itself. Do not set it in infra/compose/compose.yml: a variable of the container wins over the
 * file, and then nobody can switch it off without editing compose.
 *
 * Read at every call and not once at import, so that a test can switch it.
 */
export function isTrainingMode(): boolean {
  return import.meta.env.VITE_TRAINING_MODE === "true";
}
