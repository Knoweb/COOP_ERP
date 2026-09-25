package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/**
 * Enrols a device at a location of the caller's entity (21A section 6, EnrolDevice; doc 21
 * section 4.5).
 *
 * @param hardwareSerial   the serial printed on the hardware; unique across the federation
 * @param deviceKind       POS_TERMINAL, WORKSTATION or DRIVER_MOBILE
 * @param locationId       where the device is shipped to and kept
 * @param appVersion       the release the management agent installed at staging (doc 31)
 * @param stagingReference the management agent's staging record that attests the device
 */
public record EnrolDevice(
        String hardwareSerial, String deviceKind, UUID locationId, String appVersion, String stagingReference) {}
