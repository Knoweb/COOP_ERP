package lk.coopfed.knoweb.m2catalogue.query;

import java.util.UUID;

/**
 * Published read view of a merchandise department category (PGL-M2-08).
 * Read by the SKU editor and external modules such as M8 Reporting and M6 POS.
 */
public record DepartmentCategoryView(
        UUID departmentId,
        String code,
        String nameEn,
        String nameSi,
        String nameTa,
        String description,
        int displayOrder,
        String status) {}
