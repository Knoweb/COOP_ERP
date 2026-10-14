package lk.coopfed.knoweb.m1party.internal.bulk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.coopfed.knoweb.m1party.api.BulkRegisterNetwork;

/**
 * Parses a CSV file into a structured {@link BulkRegisterNetwork} command.
 *
 * <p>Supports both unified tabular spreadsheets (where each row represents a lane with its shop,
 * position, operator user and device) and multi-record CSVs.
 */
public final class BulkNetworkCsvParser {

    private BulkNetworkCsvParser() {}

    public static BulkRegisterNetwork parse(String csvText) {
        if (csvText == null || csvText.isBlank()) {
            return new BulkRegisterNetwork(List.of(), List.of(), List.of());
        }

        CsvTable table = CsvTable.parse(csvText);
        if (table.rows().isEmpty()) {
            return new BulkRegisterNetwork(List.of(), List.of(), List.of());
        }

        Map<String, ShopAccumulator> shops = new LinkedHashMap<>();
        Map<String, BulkRegisterNetwork.User> users = new LinkedHashMap<>();
        List<BulkRegisterNetwork.Device> devices = new ArrayList<>();

        for (CsvTable.Row row : table.rows()) {
            String shopCode = valueOf(row, "shop_code", "location_code", "code");
            String nameEn = valueOf(row, "name_en", "shop_name_en", "name", "legal_name_en");
            String nameSi = valueOf(row, "name_si", "shop_name_si", "legal_name_si");
            String nameTa = valueOf(row, "name_ta", "shop_name_ta", "legal_name_ta");
            String address = valueOf(row, "address");
            String district = valueOf(row, "district");
            String lang = valueOf(row, "language", "default_language");
            String sizeBand = valueOf(row, "size_band");

            if (!shopCode.isEmpty()) {
                ShopAccumulator acc = shops.computeIfAbsent(
                        shopCode,
                        code -> new ShopAccumulator(code, nameEn, nameSi, nameTa, address, district, lang, sizeBand));
                if (acc.nameEn.isEmpty() && !nameEn.isEmpty()) {
                    acc.nameEn = nameEn;
                }
                String posStr = valueOf(row, "position_no", "position", "till_position", "lane");
                if (!posStr.isEmpty()) {
                    try {
                        int pos = Integer.parseInt(posStr.trim());
                        acc.positions.add(pos);
                    } catch (NumberFormatException ignored) {
                        // handled during validator phase
                    }
                }
            }

            String username = valueOf(row, "username", "user_name");
            String displayName = valueOf(row, "display_name", "user_display_name", "full_name");
            String userKind = valueOf(row, "user_kind", "kind");
            String pin = valueOf(row, "pin", "user_pin");
            if (!username.isEmpty()) {
                users.put(
                        username.toLowerCase(),
                        new BulkRegisterNetwork.User(
                                username,
                                displayName.isEmpty() ? username : displayName,
                                lang.isEmpty() ? null : lang,
                                userKind.isEmpty() ? "TILL" : userKind,
                                pin.isEmpty() ? null : pin,
                                shopCode.isEmpty() ? null : shopCode));
            }

            String serial = valueOf(row, "hardware_serial", "serial", "device_serial");
            String deviceKind = valueOf(row, "device_kind");
            String appVersion = valueOf(row, "app_version", "version");
            String posStr = valueOf(row, "position_no", "position", "till_position", "lane");
            Integer posNo = null;
            if (!posStr.isEmpty()) {
                try {
                    posNo = Integer.parseInt(posStr.trim());
                } catch (NumberFormatException ignored) {
                    // validator will catch
                }
            }
            if (!serial.isEmpty()) {
                devices.add(new BulkRegisterNetwork.Device(
                        serial,
                        deviceKind.isEmpty() ? "POS_TERMINAL" : deviceKind,
                        shopCode.isEmpty() ? null : shopCode,
                        posNo,
                        appVersion.isEmpty() ? "1.0.0" : appVersion));
            }
        }

        List<BulkRegisterNetwork.Shop> shopList = new ArrayList<>();
        for (ShopAccumulator acc : shops.values()) {
            shopList.add(new BulkRegisterNetwork.Shop(
                    acc.code,
                    acc.nameEn,
                    acc.nameSi.isEmpty() ? null : acc.nameSi,
                    acc.nameTa.isEmpty() ? null : acc.nameTa,
                    acc.address.isEmpty() ? null : acc.address,
                    acc.district.isEmpty() ? null : acc.district,
                    null,
                    null,
                    acc.language.isEmpty() ? null : acc.language,
                    acc.sizeBand.isEmpty() ? null : acc.sizeBand,
                    new ArrayList<>(acc.positions)));
        }

        return new BulkRegisterNetwork(shopList, new ArrayList<>(users.values()), devices);
    }

    private static String valueOf(CsvTable.Row row, String... columnAliases) {
        for (String alias : columnAliases) {
            String val = row.get(alias);
            if (val != null && !val.isBlank()) {
                return val.strip();
            }
        }
        return "";
    }

    private static final class ShopAccumulator {
        final String code;
        String nameEn;
        final String nameSi;
        final String nameTa;
        final String address;
        final String district;
        final String language;
        final String sizeBand;
        final Set<Integer> positions = new LinkedHashSet<>();

        ShopAccumulator(
                String code,
                String nameEn,
                String nameSi,
                String nameTa,
                String address,
                String district,
                String language,
                String sizeBand) {
            this.code = code;
            this.nameEn = nameEn;
            this.nameSi = nameSi;
            this.nameTa = nameTa;
            this.address = address;
            this.district = district;
            this.language = language;
            this.sizeBand = sizeBand;
        }
    }
}
