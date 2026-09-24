package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/**
 * What M1 tells the numbering service when an entity, a location or a till position comes
 * into being (21A section 7: "NumberingService.registerSeries from your location, position and
 * device handlers"). The codes are M1's, and the kernel does not look them up: they make the
 * display prefix {@code {entity_code}[-{location_code}[-T{position}]]-{type}} (24B).
 *
 * @param docTypeCode    a code of the document type registry, for example {@code GRN}
 * @param scope          the scope the series is registered at; must be one the type allows
 * @param ownerEntityId  the issuing entity
 * @param locationId     the shop, for LOCATION and TILL_POSITION scopes; null for ENTITY
 * @param tillPositionId the position, for TILL_POSITION scope; null otherwise
 * @param entityCode     the entity's code, for example {@code M042}
 * @param locationCode   the location's code, for example {@code S03}; null for ENTITY
 * @param positionNo     the till position number; null unless TILL_POSITION
 * @param holderDeviceId the device holding the counter, when one is assigned already
 */
public record SeriesRegistration(
        String docTypeCode,
        SeriesScope scope,
        UUID ownerEntityId,
        UUID locationId,
        UUID tillPositionId,
        String entityCode,
        String locationCode,
        Integer positionNo,
        UUID holderDeviceId) {

    public static SeriesRegistration forEntity(String docTypeCode, UUID ownerEntityId, String entityCode) {
        return new SeriesRegistration(
                docTypeCode, SeriesScope.ENTITY, ownerEntityId, null, null, entityCode, null, null, null);
    }

    public static SeriesRegistration forLocation(
            String docTypeCode,
            UUID ownerEntityId,
            UUID locationId,
            String entityCode,
            String locationCode,
            UUID holderDeviceId) {
        return new SeriesRegistration(
                docTypeCode,
                SeriesScope.LOCATION,
                ownerEntityId,
                locationId,
                null,
                entityCode,
                locationCode,
                null,
                holderDeviceId);
    }

    public static SeriesRegistration forTillPosition(
            String docTypeCode,
            UUID ownerEntityId,
            UUID locationId,
            UUID tillPositionId,
            String entityCode,
            String locationCode,
            int positionNo,
            UUID holderDeviceId) {
        return new SeriesRegistration(
                docTypeCode,
                SeriesScope.TILL_POSITION,
                ownerEntityId,
                locationId,
                tillPositionId,
                entityCode,
                locationCode,
                positionNo,
                holderDeviceId);
    }
}
