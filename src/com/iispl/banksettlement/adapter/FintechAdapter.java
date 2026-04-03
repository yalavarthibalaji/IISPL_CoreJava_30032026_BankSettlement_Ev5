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
 * via REST webhooks. The JSON structure is nested with sender and receiver objects.
 *
 * RAW PAYLOAD FORMAT (Proprietary JSON — 18 fields including nested):
 * ──────────────────────────────────────────────────────────────────────
 * {
 *   "ft_ref"       : "FT-2026-00112233",          ← sourceRef
 *   "partner_id"   : "PHONEPE_PARTNER_01",         ← extra field
 *   "sender"       : {
 *     "account"    : "ACC-PH-123456",              ← debitAccountNumber
 *     "name"       : "Suresh Nair",                ← extra field
 *     "kyc_level"  : "FULL"                        ← extra field
 *   },
 *   "receiver"     : {
 *     "account"    : "ACC-PH-789012",              ← creditAccountNumber
 *     "name"       : "Kavya Stores",               ← extra field
 *     "type"       : "MERCHANT"                    ← extra field
 *   },
 *   "txn_amount"   : "12500.00",
 *   "txn_currency" : "INR",
 *   "txn_category" : "P2M",                        ← extra field
 *   "initiated_at" : "2026-04-02T13:00:00Z",       ← valueDate (date part only)
 *   "risk_score"   : "LOW",                        ← extra field
 *   "platform"     : "ANDROID",                    ← extra field
 *   "wallet_id"    : "WLT-9900112",                ← extra field
 *   "promo_code"   : "SAVE10",                     ← extra field
 *   "metadata"     : {
 *     "order_id"   : "ORD-77221",                  ← extra field
 *     "store_id"   : "STR-445"                     ← extra field
 *   }
 * }
 * ──────────────────────────────────────────────────────────────────────
 *
 * DATE FORMAT NOTE:
 *   Fintech sends initiated_at as ISO 8601 with timezone suffix Z:
 *   "2026-04-02T13:00:00Z" — we strip the "Z" and parse the date portion only.
 *
 * NESTED OBJECT PARSING:
 *   sender.account and receiver.account are inside nested JSON objects.
 *   We use extractNestedJsonField() which first extracts the nested block
 *   then extracts the field from within it.
 *
 * CANONICAL FIELDS → IncomingTransaction fields:
 *   ft_ref         → sourceRef
 *   sender.account → debitAccountNumber
 *   receiver.account → creditAccountNumber
 *   txn_amount     → amount
 *   txn_currency   → currency
 *   initiated_at   → valueDate (date part only)
 *   txn_category   → txnType (P2M/P2P → CREDIT, etc.)
 *
 * EXTRA FIELDS → normalizedPayload JSON:
 *   partner_id, sender.name, sender.kyc_level, receiver.name,
 *   receiver.type, txn_category, risk_score, platform,
 *   wallet_id, promo_code, metadata.order_id, metadata.store_id
 */
public class FintechAdapter implements TransactionAdapter {

    // Fintech sends initiated_at as "2026-04-02T13:00:00Z" — strip Z before parsing
    private static final DateTimeFormatter FINTECH_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final SourceSystem fintechSourceSystem;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // TransactionAdapter implementation
    // -----------------------------------------------------------------------

    @Override
    public IncomingTransaction adapt(String rawPayload) {

        if (rawPayload == null || rawPayload.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "FintechAdapter: rawPayload cannot be null or empty"
            );
        }

        // Skip comment lines
        if (rawPayload.trim().startsWith("#")) {
            throw new IllegalArgumentException("FintechAdapter: Skipping comment line");
        }

        // ---- Extract top-level canonical fields ----
        String sourceRef     = extractJsonField(rawPayload, "ft_ref");
        String amountStr     = extractJsonField(rawPayload, "txn_amount");
        String currency      = extractJsonField(rawPayload, "txn_currency");
        String initiatedAt   = extractJsonField(rawPayload, "initiated_at");
        String txnCategory   = extractJsonField(rawPayload, "txn_category");

        // ---- Extract nested sender and receiver account numbers ----
        // sender.account and receiver.account are inside nested JSON blocks
        String senderBlock   = extractJsonBlock(rawPayload, "sender");
        String receiverBlock = extractJsonBlock(rawPayload, "receiver");

        String senderAccount   = (senderBlock   != null) ? extractJsonField(senderBlock,   "account") : null;
        String receiverAccount = (receiverBlock != null) ? extractJsonField(receiverBlock, "account") : null;

        // ---- Validate all required fields ----
        if (sourceRef == null || amountStr == null || currency == null
                || initiatedAt == null || senderAccount == null || receiverAccount == null) {
            throw new IllegalArgumentException(
                "FintechAdapter: Missing required fields. " +
                "Expected: ft_ref, txn_amount, txn_currency, initiated_at, " +
                "sender.account, receiver.account. Got: " + rawPayload
            );
        }

        // ---- Extract extra top-level fields ----
        String partnerId    = extractJsonField(rawPayload, "partner_id");
        String riskScore    = extractJsonField(rawPayload, "risk_score");
        String platform     = extractJsonField(rawPayload, "platform");
        String walletId     = extractJsonField(rawPayload, "wallet_id");
        String promoCode    = extractJsonField(rawPayload, "promo_code");

        // ---- Extract extra nested sender fields ----
        String senderName     = (senderBlock != null) ? extractJsonField(senderBlock,   "name")      : null;
        String senderKycLevel = (senderBlock != null) ? extractJsonField(senderBlock,   "kyc_level") : null;

        // ---- Extract extra nested receiver fields ----
        String receiverName = (receiverBlock != null) ? extractJsonField(receiverBlock, "name") : null;
        String receiverType = (receiverBlock != null) ? extractJsonField(receiverBlock, "type") : null;

        // ---- Extract metadata nested fields ----
        String metadataBlock = extractJsonBlock(rawPayload, "metadata");
        String orderId  = (metadataBlock != null) ? extractJsonField(metadataBlock, "order_id") : null;
        String storeId  = (metadataBlock != null) ? extractJsonField(metadataBlock, "store_id") : null;

        // ---- Type conversions ----
        BigDecimal amount = new BigDecimal(amountStr);

        // Parse initiated_at — strip trailing "Z" (UTC timezone marker) before parsing
        // "2026-04-02T13:00:00Z" → "2026-04-02T13:00:00" → LocalDateTime → date only
        String initiatedAtClean = initiatedAt.endsWith("Z")
                ? initiatedAt.substring(0, initiatedAt.length() - 1)
                : initiatedAt;
        LocalDate valueDate = LocalDateTime.parse(initiatedAtClean, FINTECH_DATE_FORMAT).toLocalDate();

        // Map txn_category → TransactionType
        // P2M (Person to Merchant) and P2P (Person to Person) are both CREDIT to receiver
        TransactionType txnType = mapTxnCategory(txnCategory);

        // ---- Build normalizedPayload — canonical + all Fintech extra fields ----
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
            txnType, amount, currency, valueDate, normalizedPayload
        );

        txn.setDebitAccountNumber(senderAccount);
        txn.setCreditAccountNumber(receiverAccount);
        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("FINTECH_ADAPTER");
        // requiresAccountValidation stays true (default) — Fintech sends real account numbers

        return txn;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.FINTECH;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Extracts a simple string value from a JSON string by field name.
     * Handles both quoted strings and bare numeric/boolean values.
     *
     * Example: extractJsonField("{\"ft_ref\":\"FT-2026-001\"}", "ft_ref") → "FT-2026-001"
     */
    private String extractJsonField(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\":";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return null;

        int valueStart = keyIndex + searchKey.length();

        // Skip whitespace
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

    /**
     * Extracts a nested JSON object block by its key name.
     * Returns the content INSIDE the curly braces (not including the key or braces).
     *
     * Example:
     *   Input: {"sender":{"account":"ACC-001","name":"Suresh"}, ...}
     *   extractJsonBlock(input, "sender") → "\"account\":\"ACC-001\",\"name\":\"Suresh\""
     *
     * This extracted block can then be passed to extractJsonField() to get inner values.
     *
     * @param json      The full JSON string
     * @param blockName The key whose value is a nested JSON object
     * @return          The content inside the nested object, or null if not found
     */
    private String extractJsonBlock(String json, String blockName) {
        String searchKey = "\"" + blockName + "\":{";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) {
            // Also try with space: "sender" : {
            searchKey = "\"" + blockName + "\": {";
            startIndex = json.indexOf(searchKey);
            if (startIndex == -1) return null;
        }

        int blockStart = startIndex + searchKey.length(); // position just after "{"
        int depth      = 1; // we are inside one opening brace
        int i          = blockStart;

        // Walk through the string counting braces to find the matching closing brace
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i);
            if      (c == '{') depth++;
            else if (c == '}') depth--;
            if (depth > 0) i++;
            else break;
        }

        if (depth != 0) return null; // malformed JSON — no matching closing brace found

        return json.substring(blockStart, i); // content inside the block
    }

    /**
     * Maps Fintech txn_category values to our TransactionType enum.
     *
     * P2M (Person to Merchant) → CREDIT
     * P2P (Person to Person)   → CREDIT
     * REFUND                   → REVERSAL
     * FEE                      → FEE
     * Default                  → CREDIT
     */
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

    /**
     * Builds the normalizedPayload JSON string.
     * Contains all canonical fields + all Fintech-specific extra fields
     * including nested sender, receiver, and metadata values.
     */
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

    /** Returns the value if not null, otherwise returns empty string. */
    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}