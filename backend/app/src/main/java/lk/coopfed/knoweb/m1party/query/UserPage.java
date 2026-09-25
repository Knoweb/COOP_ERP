package lk.coopfed.knoweb.m1party.query;

import java.util.List;
import java.util.UUID;

public record UserPage(List<UserView> items, UUID nextCursor) {}
