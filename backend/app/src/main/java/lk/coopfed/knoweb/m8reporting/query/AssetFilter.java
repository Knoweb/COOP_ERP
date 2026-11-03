package lk.coopfed.knoweb.m8reporting.query;

import java.util.UUID;

/**
 * Filter criteria for querying the asset register.
 *
 * @param locationId optional location/branch filter
 * @param assetType  optional asset type filter
 * @param status     optional status filter
 */
public record AssetFilter(UUID locationId, String assetType, String status) {

    public static final AssetFilter ALL = new AssetFilter(null, null, null);
}
