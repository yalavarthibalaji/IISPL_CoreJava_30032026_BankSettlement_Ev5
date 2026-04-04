package com.iispl.banksettlement.adapter;

import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.entity.SourceSystem;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.enums.ProtocolType;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * FintechAdapter — Adapter for third-party Fintech partner transactions.
 *
 * Fintech partners (PhonePe, Razorpay, PayU etc.) send proprietary JSON
 * via REST webhooks with nested sender/receiver objects.
 *
 * RAW PAYLOAD FORMAT (Proprietary JSON — 18 fields including nested):
 * ──────────────────────────────────────────────────────────────────────
 * {
 *   "ft_ref"       : "FT-2026-00112233",          ← sourceRef
 *   "partner_id"   : "PHONEPE_PARTNER_01",
 *   "sender"       : {
 *     "account"    : "ACC-PH-123456",              ← debitAccount (normalizedPayload)
 *     "name"       : "Suresh Nair",
 *     "kyc_level"  : "FULL"
 *   },
 *   "receiver"     : {
 *     "account"    : "ACC-PH-789012",              ← creditAccount (normalizedPayload)
 *     "name"       : "Kavya Stores",
 *     "type"       : "MERCHANT"
 *   },
 *   "txn_amount"   : "12500.00",
 *   "txn_currency" : "INR",
 *   "txn_category" : "P2M",
 *   "initiated_at" : "2026-04-02T13:00:00Z",
 *   "risk_score"   : "LOW",
 *   "platform"     : "ANDROID",
 *   "wallet_id"    : "WLT-9900112",
 *   "promo_code"   : "SAVE10",
 *   "metadata"     : { "order_id":"ORD-77221", "store_id":"STR-445" }
 * }
 * ──────────────────────────────────────────────────────────────────────
 *
 * CHANGE LOG (v2):
 *   - currency removed from IncomingTransaction field (INR-only system).
 *     txn_currency still read and stored in normalizedPayload for audit.
 *   - sender.account / receiver.account no longer set as debitAccountNumber /
 *     creditAccountNumber (those fields removed). They now live ONLY inside
 *     normalizedPayload as "debitAccount" and "creditAccount".
 *   - requiresAccountValidation removed.
 *   - IncomingTransaction constructor no longer takes currency parameter.
 */
public class FintechAdapter implements TransactionAdapter {

    private static final DateTimeFormatter FINTECH_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final SourceSystem fintechSourceSystem;

    public FintechAdapter() {
        this.fintechSourceSystem = new SourceSystem(
            "FINTECH",
            ProtocolType.REST_API,
            "{\"endpoint\":\"https://api.bank.com/fintech/webhook\",\"authType\":\"API_KEY\"}",
            true,
            "fintech-integration@bank.com"
        );
        this.fintechSourceSystem.setSourceSystemId(6L);
        this.fintechSourceSystem.setCreatedBy("SYSTEM");
    }

    @Override
    public IncomingTransaction adapt(String rawPayload) {

        if (rawPayload == null || rawPayload.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "FintechAdapter: rawPayload cannot be null or empty"
            );
        }

        if (rawPayload.trim().startsWith("#")) {
            throw new IllegalArgumentException("FintechAdapter: Skipping comment line");
        }

        String sourceRef   = extractJsonField(rawPayload, "ft_ref");
        String amountStr   = extractJsonField(rawPayload, "txn_amount");
        String currency    = extractJsonField(rawPayload, "txn_currency");
        String initiatedAt = extractJsonField(rawPayload, "initiated_at");
        String txnCategory = extractJsonField(rawPayload, "txn_category");

        String senderBlock   = extractJsonBlock(rawPayload, "sender");
        String receiverBlock = extractJsonBlock(rawPayload, "receiver");

        String senderAccount   = (senderBlock   != null) ? extractJsonField(senderBlock,   "account") : null;
        String receiverAccount = (receiverBlock != null) ? extractJsonField(receiverBlock, "account") : null;

        if (sourceRef == null || amountStr == null || currency == null
                || initiatedAt == null || senderAccount == null || receiverAccount == null) {
            throw new IllegalArgumentException(
                "FintechAdapter: Missing required fields. " +
                "Expected: ft_ref, txn_amount, txn_currency, initiated_at, " +
                "sender.account, receiver.account. Got: " + rawPayload
            );
        }

        String partnerId  = extractJsonField(rawPayload, "partner_id");
        String riskScore  = extractJsonField(rawPayload, "risk_score");
        String platform   = extractJsonField(rawPayload, "platform");
        String walletId   = extractJsonField(rawPayload, "wallet_id");
        String promoCode  = extractJsonField(rawPayload, "promo_code");

        String senderName     = (senderBlock   != null) ? extractJsonField(senderBlock,   "name")      : null;
        String senderKycLevel = (senderBlock   != null) ? extractJsonField(senderBlock,   "kyc_level") : null;
        String receiverName   = (receiverBlock != null) ? extractJsonField(receiverBlock, "name")      : null;
        String receiverType   = (receiverBlock != null) ? extractJsonField(receiverBlock, "type")      : null;

        String metadataBlock = extractJsonBlock(rawPayload, "metadata");
        String orderId = (metadataBlock != null) ? extractJsonField(metadataBlock, "order_id") : null;
        String storeId = (metadataBlock != null) ? extractJsonField(metadataBlock, "store_id") : null;

        BigDecimal amount = new BigDecimal(amountStr);

        String initiatedAtClean = initiatedAt.endsWith("Z")
                ? initiatedAt.substring(0, initiatedAt.length() - 1)
                : initiatedAt;
        LocalDate valueDate = LocalDateTime.parse(initiatedAtClean, FINTECH_DATE_FORMAT).toLocalDate();

        TransactionType txnType = mapTxnCategory(txnCategory);

        String normalizedPayload = buildNormalizedPayload(
            sourceRef, txnType, amount, currency, valueDate,
            senderAccount, receiverAccount,
            partnerId, senderName, senderKycLevel,
            receiverName, receiverType,
            nullSafe(txnCategory), riskScore, platform,
            walletId, promoCode, orderId, storeId
        );

        IncomingTransaction txn = new IncomingTransaction(
            fintechSourceSystem, sourceRef, rawPayload,
            txnType, amount, valueDate, normalizedPayload
        );

        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("FINTECH_ADAPTER");

        return txn;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.FINTECH;
    }

    private String extractJsonField(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\":";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return null;
        int valueStart = keyIndex + searchKey.length();
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') {
            valueStart++;
        }
        boolean isQuoted = (valueStart < json.length() && json.charAt(valueStart) == '"');
        if (isQuoted) {
            int openQuote  = valueStart;
            int closeQuote = json.indexOf('"', openQuote + 1);
            if (closeQuote == -1) return null;
            return json.substring(openQuote + 1, closeQuote);
        } else {
            int endIndex = json.indexOf(',', valueStart);
            if (endIndex == -1) endIndex = json.indexOf('}', valueStart);
            if (endIndex == -1) endIndex = json.length();
            return json.substring(valueStart, endIndex).trim();
        }
    }

    private String extractJsonBlock(String json, String blockName) {
        String searchKey = "\"" + blockName + "\":{";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) {
            searchKey = "\"" + blockName + "\": {";
            startIndex = json.indexOf(searchKey);
            if (startIndex == -1) return null;
        }
        int blockStart = startIndex + searchKey.length();
        int depth = 1;
        int i = blockStart;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i);
            if      (c == '{') depth++;
            else if (c == '}') depth--;
            if (depth > 0) i++;
            else break;
        }
        if (depth != 0) return null;
        return json.substring(blockStart, i);
    }

    private TransactionType mapTxnCategory(String txnCategory) {
        if (txnCategory == null) return TransactionType.CREDIT;
        switch (txnCategory.toUpperCase().trim()) {
            case "P2M":
            case "P2P":
                return TransactionType.CREDIT;
            case "REFUND":
                return TransactionType.REVERSAL;
            case "FEE":
                return TransactionType.FEE;
            default:
                return TransactionType.CREDIT;
        }
    }

    private String buildNormalizedPayload(String sourceRef, TransactionType txnType,
                                          BigDecimal amount, String currency,
                                          LocalDate valueDate,
                                          String debitAcc, String creditAcc,
                                          String partnerId,
                                          String senderName, String senderKycLevel,
                                          String receiverName, String receiverType,
                                          String txnCategory, String riskScore,
                                          String platform, String walletId,
                                          String promoCode, String orderId,
                                          String storeId) {
        return "{"
            + "\"source\":\"FINTECH\","
            + "\"sourceRef\":\"" + sourceRef + "\","
            + "\"txnType\":\"" + txnType.name() + "\","
            + "\"amount\":" + amount + ","
            + "\"currency\":\"" + currency + "\","
            + "\"valueDate\":\"" + valueDate + "\","
            + "\"debitAccount\":\"" + debitAcc + "\","
            + "\"creditAccount\":\"" + creditAcc + "\","
            + "\"partnerId\":\"" + nullSafe(partnerId) + "\","
            + "\"senderName\":\"" + nullSafe(senderName) + "\","
            + "\"senderKycLevel\":\"" + nullSafe(senderKycLevel) + "\","
            + "\"receiverName\":\"" + nullSafe(receiverName) + "\","
            + "\"receiverType\":\"" + nullSafe(receiverType) + "\","
            + "\"txnCategory\":\"" + nullSafe(txnCategory) + "\","
            + "\"riskScore\":\"" + nullSafe(riskScore) + "\","
            + "\"platform\":\"" + nullSafe(platform) + "\","
            + "\"walletId\":\"" + nullSafe(walletId) + "\","
            + "\"promoCode\":\"" + nullSafe(promoCode) + "\","
            + "\"orderId\":\"" + nullSafe(orderId) + "\","
            + "\"storeId\":\"" + nullSafe(storeId) + "\""
            + "}";
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
