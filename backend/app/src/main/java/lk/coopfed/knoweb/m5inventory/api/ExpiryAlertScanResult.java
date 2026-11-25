package lk.coopfed.knoweb.m5inventory.api;

/**
 * Summary outcome of an expiry and slow-moving alert evaluation scan (PGL-M2-07).
 */
public record ExpiryAlertScanResult(int nearExpiryAlertsEmitted, int slowMovingAlertsEmitted, int totalEvaluated) {}
