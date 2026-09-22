package lk.coopfed.knoweb.m1party.query;

import java.util.List;

public record EntityPage(List<EntityView> items, String nextCursor) {}
