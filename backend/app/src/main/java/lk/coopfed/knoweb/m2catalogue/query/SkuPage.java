package lk.coopfed.knoweb.m2catalogue.query;

import java.util.List;

public record SkuPage(List<SkuView> items, Integer nextOffset) {}
