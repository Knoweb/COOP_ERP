package lk.coopfed.knoweb.m2catalogue.api;

/**
 * Command to define a new merchandise department category (PGL-M2-08).
 * Only the Federation may define governed department categories (cat.department.manage).
 */
public record DefineDepartmentCategory(
        String code, String nameEn, String nameSi, String nameTa, String description, Integer displayOrder) {}
