package lk.coopfed.knoweb.m8reporting.internal.report;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;

/**
 * The names a report shows, resolved when it is read (28A section 2: "M2 query: ResolveAlias;
 * names for rendering"): an entity's legal name, a location's name and a SKU's code and name, in
 * the caller's language (English when that one is empty), through M1's and M2's queries under
 * the caller's scope. A name the scope may not read is shown as the start of its id, as the web
 * shell shows an unnamed entity; the projections never store a name. One instance per report,
 * so each id is asked once.
 */
final class Names {

    private final PartyQueries party;
    private final CatalogueQueries catalogue;
    private final ScopeContext scope;
    private final Map<UUID, String> entities = new HashMap<>();
    private final Map<UUID, String> locations = new HashMap<>();
    private final Map<UUID, SkuView> skus = new HashMap<>();

    Names(PartyQueries party, CatalogueQueries catalogue, ScopeContext scope) {
        this.party = party;
        this.catalogue = catalogue;
        this.scope = scope;
    }

    String entity(UUID id) {
        if (id == null) {
            return "";
        }
        return entities.computeIfAbsent(
                id, key -> party.getEntity(key, scope).map(this::name).orElseGet(() -> shortId(key)));
    }

    String location(UUID id) {
        if (id == null) {
            return "";
        }
        return locations.computeIfAbsent(
                id, key -> party.getLocation(key, scope).map(this::name).orElseGet(() -> shortId(key)));
    }

    String skuCode(UUID id) {
        SkuView sku = sku(id);
        return sku == null ? shortId(id) : sku.skuCode();
    }

    String skuName(UUID id) {
        SkuView sku = sku(id);
        return sku == null ? "" : pick(sku.nameEn(), sku.nameSi(), sku.nameTa());
    }

    private SkuView sku(UUID id) {
        if (id == null) {
            return null;
        }
        return skus.computeIfAbsent(id, key -> catalogue.getSku(key, scope).orElse(null));
    }

    private String name(EntityView entity) {
        return pick(entity.legalNameEn(), entity.legalNameSi(), entity.legalNameTa());
    }

    private String name(LocationView location) {
        String name = pick(location.nameEn(), location.nameSi(), location.nameTa());
        return location.locationCode() + " " + name;
    }

    private String pick(String en, String si, String ta) {
        String chosen =
                switch (scope.lang()) {
                    case "si" -> si;
                    case "ta" -> ta;
                    default -> en;
                };
        return chosen == null || chosen.isBlank() ? (en == null ? "" : en) : chosen;
    }

    private static String shortId(UUID id) {
        String text = id.toString();
        return "…" + text.substring(text.length() - 6);
    }
}
