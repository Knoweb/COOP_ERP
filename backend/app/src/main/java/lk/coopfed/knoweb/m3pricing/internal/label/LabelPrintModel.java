package lk.coopfed.knoweb.m3pricing.internal.label;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.Formats;
import lk.coopfed.knoweb.kernel.api.Messages;
import org.springframework.stereotype.Component;

/** Formats all shelf-label values before the escaped Thymeleaf template sees them. */
@Component
class LabelPrintModel {

    private final Formats formats;
    private final Messages messages;

    LabelPrintModel(Formats formats, Messages messages) {
        this.formats = formats;
        this.messages = messages;
    }

    Map<String, Object> model(List<Item> items, Locale locale) {
        List<Map<String, String>> labels = new ArrayList<>();
        for (Item item : items) {
            Map<String, String> label = new LinkedHashMap<>();
            label.put("code", item.code());
            label.put("nameSi", fallback(item.nameSi(), item.nameEn(), item.code()));
            label.put("nameTa", fallback(item.nameTa(), item.nameEn(), item.code()));
            label.put("nameEn", fallback(item.nameEn(), item.code(), item.code()));
            label.put("price", formats.moneyWithCurrency(item.price(), locale));
            label.put("unit", item.unit());
            label.put(
                    "mrpNote", messages.t(item.hasPrintedMrp() ? "m3.label.mrp.printed" : "m3.label.mrp.none", locale));
            labels.add(label);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", labels);
        data.put("empty", messages.t("m3.label.empty", locale));
        data.put("priceLabel", messages.t("m3.label.price", locale));
        data.put("unitLabel", messages.t("m3.label.unit", locale));
        return data;
    }

    private static String fallback(String preferred, String english, String code) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        if (english != null && !english.isBlank()) {
            return english;
        }
        return code;
    }

    record Item(
            String code,
            String nameSi,
            String nameTa,
            String nameEn,
            BigDecimal price,
            String unit,
            boolean hasPrintedMrp) {}
}
