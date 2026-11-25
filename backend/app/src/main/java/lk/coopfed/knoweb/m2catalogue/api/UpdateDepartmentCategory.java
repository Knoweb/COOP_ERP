package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

/**
 * Command to update an existing merchandise department category (PGL-M2-08).
 * Only the Federation may update department categories (cat.department.manage).
 */
public record UpdateDepartmentCategory(
        UUID departmentId,
        String nameEn,
        String nameSi,
        String nameTa,
        String description,
        Integer displayOrder,
        String status) {}
