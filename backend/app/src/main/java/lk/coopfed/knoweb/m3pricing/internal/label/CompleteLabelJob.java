package lk.coopfed.knoweb.m3pricing.internal.label;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m3pricing.query.LabelRefusal;

/** The worker's terminal outcome for one queued label job. */
record CompleteLabelJob(UUID jobId, String objectKey, List<LabelRefusal> refused, int renderedCount, boolean failed) {

    CompleteLabelJob {
        refused = refused == null ? List.of() : List.copyOf(refused);
    }

    static CompleteLabelJob rendered(UUID jobId, String objectKey, List<LabelRefusal> refused, int renderedCount) {
        return new CompleteLabelJob(jobId, objectKey, refused, renderedCount, false);
    }

    static CompleteLabelJob failed(UUID jobId, List<LabelRefusal> refused) {
        return new CompleteLabelJob(jobId, null, refused, 0, true);
    }
}
