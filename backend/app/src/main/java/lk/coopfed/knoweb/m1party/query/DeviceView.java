package lk.coopfed.knoweb.m1party.query;

import java.time.Instant;
import java.util.UUID;

/**
 * One device as the asset register shows it (21A section 7, ListDevices; doc 21 section 8:
 * serial, position, version, last seen).
 *
 * @param tillPositionId the position it holds, or null
 * @param positionNo     that position's number at its shop, or null
 * @param primaryTill    whether that position is its shop's primary till
 * @param lastSeenAt     the last heartbeat the sync gateway recorded, or null
 */
public record DeviceView(
        UUID deviceId,
        UUID ownerEntityId,
        UUID locationId,
        String hardwareSerial,
        String deviceKind,
        String status,
        UUID tillPositionId,
        Integer positionNo,
        boolean primaryTill,
        String appVersion,
        Instant enrolledAt,
        Instant lastSeenAt) {}
