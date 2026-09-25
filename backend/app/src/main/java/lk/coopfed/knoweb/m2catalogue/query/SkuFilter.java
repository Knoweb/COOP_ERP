package lk.coopfed.knoweb.m2catalogue.query;

public record SkuFilter(String status, String query, String language, Integer offset, Integer limit) {

    public int normalizedOffset() {
        return offset == null || offset < 0 ? 0 : offset;
    }

    public int normalizedLimit() {
        if (limit == null) {
            return 50;
        }
        return Math.max(1, Math.min(limit, 200));
    }
}
