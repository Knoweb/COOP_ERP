package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** tax_category.defined.v1 */
public record TaxCategoryDefined(UUID taxCategoryId, UUID ownerEntityId, String code) implements DomainEvent {

    public static final String TYPE = "tax_category.defined.v1";
}
