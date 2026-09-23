package lk.coopfed.knoweb.m1party.api;

public record RegisterEntity(
        String entityCode,
        String entityType,
        String legalNameEn,
        String legalNameSi,
        String legalNameTa,
        String registrationNo,
        String vatRegistrationNo,
        String district,
        String defaultLanguage,
        Integer financialYearStartMonth) {}
