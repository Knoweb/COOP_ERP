package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when a department category is updated (PGL-M2-08).
 */
public record DepartmentCategoryUpdated(
        UUID departmentId, String code, String nameEn, String nameSi, String nameTa, String status)
        implements DomainEvent {

    public static final String TYPE = "catalogue.department_category.updated.v1";
}
