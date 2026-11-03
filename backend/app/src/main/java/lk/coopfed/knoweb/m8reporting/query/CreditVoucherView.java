package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * System document view representing a credit purchase voucher (CPV).
 */
public record CreditVoucherView(
        String voucherNumber,
        UUID invoiceId,
        String invoiceDocNumber,
        LocalDate date,
        UUID buyerEntityId,
        String buyerName,
        String buyerCode,
        UUID sellerEntityId,
        String sellerName,
        String sellerCode,
        String sellerVatNo,
        String buyerVatNo,
        String sourceTransactionReference,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal grossAmount,
        String status,
        String notes,
        List<CreditVoucherLineView> lines) {}
