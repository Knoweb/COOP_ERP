package lk.coopfed.knoweb.m1party.api;

import java.util.List;

/**
 * Bulk registration command for a retail network (CAT-08): shops, till positions, users and
 * devices in a single batch process within one transaction.
 */
public record BulkRegisterNetwork(List<Shop> shops, List<User> users, List<Device> devices) {

    public BulkRegisterNetwork {
        shops = shops == null ? List.of() : List.copyOf(shops);
        users = users == null ? List.of() : List.copyOf(users);
        devices = devices == null ? List.of() : List.copyOf(devices);
    }

    /** A shop location and its optional till positions. */
    public record Shop(
            String locationCode,
            String nameEn,
            String nameSi,
            String nameTa,
            String address,
            String district,
            Double geoLat,
            Double geoLng,
            String language,
            String sizeBand,
            List<Integer> tillPositions) {

        public Shop {
            tillPositions = tillPositions == null ? List.of() : List.copyOf(tillPositions);
        }
    }

    /** A user to create for the entity with optional till credentials (PIN). */
    public record User(
            String username, String displayName, String language, String userKind, String pin, String locationCode) {}

    /** A device to enrol and optionally assign to a shop lane. */
    public record Device(
            String hardwareSerial, String deviceKind, String locationCode, Integer positionNo, String appVersion) {}
}
