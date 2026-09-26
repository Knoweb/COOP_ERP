-- SearchSku matches a prefix of sku_code (22A section 7: "prefix on sku_code"). The unique
-- index of the column uses the database collation, which LIKE 'SKU-AB%' cannot use; an index
-- with the pattern operator class can.
CREATE INDEX sku_code_prefix ON catalogue.sku (sku_code varchar_pattern_ops);
