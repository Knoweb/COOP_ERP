package lk.coopfed.knoweb.m3pricing.internal.label;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m3pricing.internal.ceiling.ControlPriceStore;
import lk.coopfed.knoweb.m3pricing.internal.label.LabelJobStore.Row;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListStore;
import lk.coopfed.knoweb.m3pricing.query.ControlPriceView;
import lk.coopfed.knoweb.m3pricing.query.LabelRefusal;
import lk.coopfed.knoweb.m3pricing.query.PriceListLineView;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads the print-date inputs in a short transaction, then renders outside it. */
@Component
class LabelRenderer {

    private final PartyQueries party;
    private final CatalogueQueries catalogue;
    private final PriceListStore lists;
    private final ControlPriceStore controlPrices;
    private final LabelPrintModel printModel;
    private final A4Renderer renderer;

    LabelRenderer(
            PartyQueries party,
            CatalogueQueries catalogue,
            PriceListStore lists,
            ControlPriceStore controlPrices,
            LabelPrintModel printModel,
            A4Renderer renderer) {
        this.party = party;
        this.catalogue = catalogue;
        this.lists = lists;
        this.controlPrices = controlPrices;
        this.printModel = printModel;
        this.renderer = renderer;
    }

    @Transactional(readOnly = true)
    public Prepared prepare(Row job, LocalDate printDate, ScopeContext scope) {
        LocationView location = party.getLocation(job.locationId(), scope)
                .filter(found -> found.ownerEntityId().equals(job.ownerEntityId()))
                .orElseThrow(() -> new ProblemException("m3.label.location_not_owned"));
        PriceListView list = lists.rootOfKind("RETAIL", job.ownerEntityId())
                .flatMap(root -> lists.versionInForce(root, printDate))
                .orElseThrow(() -> new ProblemException("m3.label.retail_list_not_found"));
        Map<UUID, PriceListLineView> lines = new LinkedHashMap<>();
        for (PriceListLineView line : lists.lines(list.priceListId())) {
            lines.putIfAbsent(line.skuId(), line);
        }

        List<LabelPrintModel.Item> eligible = new ArrayList<>();
        List<LabelRefusal> refused = new ArrayList<>();
        for (UUID skuId : job.skuIds()) {
            PriceListLineView line = lines.get(skuId);
            if (line == null) {
                throw new ProblemException("m3.label.sku_not_on_list", Map.of("skuId", skuId));
            }
            SkuView sku = catalogue
                    .getSku(skuId, scope)
                    .orElseThrow(() -> new ProblemException("m3.label.sku_not_on_list", Map.of("skuId", skuId)));
            ControlPriceView ceiling =
                    controlPrices.ceilingFor(skuId, line.uomCode(), printDate).orElse(null);
            if (ceiling != null && line.price().compareTo(ceiling.ceilingPrice()) > 0) {
                refused.add(new LabelRefusal(
                        skuId,
                        sku.skuCode(),
                        LabelRefusal.ABOVE_CONTROL_PRICE,
                        line.price(),
                        ceiling.ceilingPrice(),
                        ceiling.gazetteReference()));
                continue;
            }
            eligible.add(new LabelPrintModel.Item(
                    sku.skuCode(),
                    sku.nameSi(),
                    sku.nameTa(),
                    sku.nameEn(),
                    line.price(),
                    line.uomCode(),
                    sku.hasPrintedMrp()));
        }

        Locale locale = locale(location.language());
        return new Prepared(job.template(), printModel.model(eligible, locale), locale, refused, eligible.size());
    }

    /** No transaction: Chromium and object storage may take seconds (A4Renderer contract). */
    String render(Prepared prepared, ScopeContext scope) {
        return renderer.render(prepared.template(), prepared.model(), prepared.locale(), scope)
                .objectKey();
    }

    private static Locale locale(String language) {
        return switch (language) {
            case "si", "ta", "en" -> Locale.forLanguageTag(language);
            default -> Locale.ENGLISH;
        };
    }

    record Prepared(
            String template, Map<String, Object> model, Locale locale, List<LabelRefusal> refused, int renderedCount) {

        Prepared {
            model = Map.copyOf(model);
            refused = List.copyOf(refused);
        }
    }
}
