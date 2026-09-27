package lk.coopfed.knoweb.m8reporting.internal.report;

import java.util.UUID;

/**
 * The outcome of a run, from the worker that rendered it: the PDF's object key, or the
 * renderer's message id when it failed. Not on the HTTP surface.
 */
record CompleteReportRun(UUID runId, String objectKey, String errorCode) {}
