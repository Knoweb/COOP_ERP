package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/**
 * Assigns a device to a till position (21A section 6 and 6.1, AssignDeviceToPosition). When the
 * position is still held by a suspended device, this is the replacement of doc 21 flow 6.6: the
 * position's counters move to the new device, which needs the old device's outbox drained or
 * its loss recorded.
 *
 * @param deviceId           the device to assign
 * @param tillPositionId     the position it takes
 * @param reasonCode         why (doc 21 section 5: "device, position, reason")
 * @param reasonText         optional words beside the code
 * @param outboxLossRecorded the administrator records that the outbox of the device giving the
 *                           position up cannot be drained (lost, stolen, dead): the numbers it
 *                           took and never sent become a documented gap (doc 32 section 8)
 */
public record AssignDeviceToPosition(
        UUID deviceId, UUID tillPositionId, String reasonCode, String reasonText, boolean outboxLossRecorded) {}
