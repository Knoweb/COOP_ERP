package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/**
 * Names the primary till of a shop (21A section 6, SetPrimaryTill). The shop's location series
 * move to the device at the new primary till, which needs the device at the old one drained or
 * its loss recorded, as any counter transfer does (21A section 6.1).
 *
 * @param locationId         the shop
 * @param tillPositionId     the position that becomes the primary till
 * @param reasonCode         why
 * @param reasonText         optional words beside the code
 * @param outboxLossRecorded the administrator records that the device at the old primary till
 *                           cannot be shown drained: the numbers it took from the location
 *                           series and never sent become a documented gap (doc 32 section 8)
 */
public record SetPrimaryTill(
        UUID locationId, UUID tillPositionId, String reasonCode, String reasonText, boolean outboxLossRecorded) {

    /** Without a recorded loss: the common case, the old till drained or no device to move from. */
    public SetPrimaryTill(UUID locationId, UUID tillPositionId, String reasonCode, String reasonText) {
        this(locationId, tillPositionId, reasonCode, reasonText, false);
    }
}
