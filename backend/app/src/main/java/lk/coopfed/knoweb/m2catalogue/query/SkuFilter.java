package lk.coopfed.knoweb.m2catalogue.query;

public record SkuFilter(String status, String query, String language, Integer offset, Integer limit) {

    /**
     * The deepest page an offset may reach. 22A section 4 asks for a cursor; until the list moves
     * to one, the offset is capped so that a caller cannot make the database walk the whole
     * catalogue to skip it. Past the cap the list says there is no next page.
     */
    public static final int MAX_OFFSET = 10_000;

    public int normalizedOffset() {
        return offset == null || offset < 0 ? 0 : Math.min(offset, MAX_OFFSET);
    }

    public int normalizedLimit() {
        if (limit == null) {
            return 50;
        }
        return Math.max(1, Math.min(limit, 200));
    }
}
