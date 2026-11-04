package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when a new department category is defined (PGL-M2-08).
 */
public record DepartmentCategoryDefined(
        UUID departmentId, String code, String nameEn, String nameSi, String nameTa, String status)
        implements DomainEvent {

    public static final String TYPE = "catalogue.department_category.defined.v1";
}
