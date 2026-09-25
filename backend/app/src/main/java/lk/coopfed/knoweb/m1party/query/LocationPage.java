package lk.coopfed.knoweb.m1party.query;

import java.util.List;

public record LocationPage(List<LocationView> items, String nextCursor) {}
