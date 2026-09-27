package lk.coopfed.knoweb.demo;

import java.util.List;
import java.util.UUID;

/**
 * Who and where of the demo: the entities, locations and users that
 * seed/m1party/demo-parties.demo.sql and seed/m1security/demo-users.demo.sql load, and the dev realm
 * (infra/compose/realm-dev.json) signs in. The ids are fixed there because the token carries the
 * user's home entity; the loader only refers to them. docs/DEMO.md has the same table for people.
 */
final class DemoCast {

    private DemoCast() {}

    /** The Federation of the development seed (entities.dev.sql), the system entity of compose. */
    static final UUID FEDERATION = UUID.fromString("0190f000-0000-7000-8000-000000000001");

    static final UUID D101 = id("0000000000e1");
    static final UUID D102 = id("0000000000e2");
    static final UUID M101 = id("0000000000e3");
    static final UUID M102 = id("0000000000e4");
    static final UUID M103 = id("0000000000e5");

    static final UUID FEDERATION_WAREHOUSE = id("000000000101");
    static final UUID D101_WAREHOUSE = id("000000000111");
    static final UUID D102_WAREHOUSE = id("000000000121");
    static final UUID M101_TOWN_SHOP = id("000000000132");
    static final UUID M101_HETTIPOLA_SHOP = id("000000000133");
    static final UUID M102_SHOP = id("000000000142");
    static final UUID M103_SHOP = id("000000000152");

    /** A demo user acting in one entity, at one location or (null) entity-wide. */
    record Actor(String username, UUID userId, UUID entityId, UUID locationId) {}

    static final Actor FED_STEWARD = actor("fed-steward", "000000000201", FEDERATION, null);
    static final Actor FED_PRICING = actor("fed-pricing", "000000000202", FEDERATION, null);
    static final Actor FED_STORES = actor("fed-stores", "000000000203", FEDERATION, FEDERATION_WAREHOUSE);
    static final Actor FED_ACCOUNTS = actor("fed-accounts", "000000000205", FEDERATION, null);
    static final Actor D101_BUYER = actor("d101-buyer", "000000000211", D101, null);
    static final Actor D101_STORES = actor("d101-stores", "000000000212", D101, D101_WAREHOUSE);
    static final Actor D101_ACCOUNTS = actor("d101-accounts", "000000000213", D101, null);
    static final Actor D102_BUYER = actor("d102-buyer", "000000000221", D102, null);
    static final Actor D102_STORES = actor("d102-stores", "000000000222", D102, D102_WAREHOUSE);
    static final Actor D102_ACCOUNTS = actor("d102-accounts", "000000000223", D102, null);
    static final Actor M101_MANAGER = actor("m101-manager", "000000000232", M101, null);
    static final Actor M102_MANAGER = actor("m102-manager", "000000000242", M102, null);
    static final Actor M103_MANAGER = actor("m103-manager", "000000000252", M103, null);

    /** A shop, the society manager who sets it up, and how many till positions it has. */
    record Shop(UUID locationId, Actor manager, int tills) {}

    static final List<Shop> SHOPS = List.of(
            new Shop(M101_TOWN_SHOP, M101_MANAGER, 2),
            new Shop(M101_HETTIPOLA_SHOP, M101_MANAGER, 1),
            new Shop(M102_SHOP, M102_MANAGER, 1),
            new Shop(M103_SHOP, M103_MANAGER, 1));

    /** A distributor: who prices and sells for it, its stores and accounts, and its societies. */
    record Distributor(
            UUID entityId, String listName, Actor commercial, Actor stores, Actor accounts, List<UUID> societies) {}

    static final List<Distributor> DISTRIBUTORS = List.of(
            new Distributor(
                    D101,
                    "Wayamba trade list for societies",
                    D101_BUYER,
                    D101_STORES,
                    D101_ACCOUNTS,
                    List.of(M101, M102)),
            new Distributor(
                    D102, "Northern trade list for societies", D102_BUYER, D102_STORES, D102_ACCOUNTS, List.of(M103)));

    static final String FEDERATION_LIST_NAME = "Federation trade list for distributors";

    private static UUID id(String tail) {
        return UUID.fromString("0190f0de-0000-7000-8000-" + tail);
    }

    private static Actor actor(String username, String tail, UUID entityId, UUID locationId) {
        return new Actor(username, id(tail), entityId, locationId);
    }
}
