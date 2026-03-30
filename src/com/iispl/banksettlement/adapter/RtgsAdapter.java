package com.iispl.banksettlement.adapter;

import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.entity.SourceSystem;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.enums.ProtocolType;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * RtgsAdapter — Adapter for the Real-Time Gross Settlement (RTGS) system.
 *
 * RTGS is used for high-value, time-critical interbank transfers.
 * In India, RTGS is managed by the Reserve Bank of India (RBI).
 * Minimum transaction value: Rs. 2,00,000.
 *
 * RTGS sends transactions as JSON via REST API webhooks.
 *
 * EXPECTED RAW PAYLOAD FORMAT FROM RTGS (JSON string):
 *   {
 *     "rtgsRef"   : "RTGS20240615001",
 *     "txnType"   : "CREDIT",
 *     "amount"    : "500000.00",
 *     "currency"  : "INR",
 *     "valueDate" : "2024-06-15"
 *   }
 *
 * NOTE: In real production code, you would use a JSON library (like Jackson or Gson).
 * Since this is a Core Java training project with no external libraries, we do
 * basic manual JSON parsing here using String operations.
 *
 * Implements: TransactionAdapter (Strategy Pattern)
 */
public class RtgsAdapter implements TransactionAdapter {

    private SourceSystem rtgsSourceSystem;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public RtgsAdapter() {
        this.rtgsSourceSystem = new SourceSystem(
            "RTGS",
            ProtocolType.REST_API,             // RTGS pushes via REST webhooks
            "{\"endpoint\":\"https://rtgs.rbi.org.in/webhook\"}",
            true,
            "rtgs-support@rbi.org.in"
        );
        this.rtgsSourceSystem.setSourceSystemId(2L);
        this.rtgsSourceSystem.setCreatedBy("SYSTEM");
    }

    // -----------------------------------------------------------------------
    // TransactionAdapter implementation
    // -----------------------------------------------------------------------

    /**
     * Parses an RTGS JSON payload and returns a canonical IncomingTransaction.
     *
     * Example input:
     *   {"rtgsRef":"RTGS20240615001","txnType":"CREDIT","amount":"500000.00","currency":"INR","valueDate":"2024-06-15"}
     */
    @Override
    public IncomingTransaction adapt(String rawPayload) {

        if (rawPayload == null || rawPayload.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "RtgsAdapter: rawPayload cannot be null or empty"
            );
        }

        // Manual JSON field extraction (no external library needed)
        String sourceRef   = extractJsonField(rawPayload, "rtgsRef");
        String txnTypeStr  = extractJsonField(rawPayload, "txnType");
        String amountStr   = extractJsonField(rawPayload, "amount");
        String currency    = extractJsonField(rawPayload, "currency");
        String valueDateStr = extractJsonField(rawPayload, "valueDate");

        // Validate that all required fields were found
        if (sourceRef == null || txnTypeStr == null || amountStr == null
                || currency == null || valueDateStr == null) {
            throw new IllegalArgumentException(
                "RtgsAdapter: Missing required fields in payload: " + rawPayload
            );
        }

        BigDecimal amount     = new BigDecimal(amountStr);
        LocalDate  valueDate  = LocalDate.parse(valueDateStr);
        TransactionType txnType = TransactionType.valueOf(txnTypeStr.toUpperCase());

        // RTGS minimum amount validation — must be >= 2,00,000 INR
        if ("INR".equalsIgnoreCase(currency) && amount.compareTo(new BigDecimal("200000")) < 0) {
            throw new IllegalArgumentException(
                "RtgsAdapter: RTGS transactions must be minimum Rs. 2,00,000. Got: " + amount
            );
        }

        String normalizedPayload = buildNormalizedPayload(
            sourceRef, txnType, amount, currency, valueDate
        );

        IncomingTransaction txn = new IncomingTransaction(
            rtgsSourceSystem,
            sourceRef,
            rawPayload,
            txnType,
            amount,
            currency,
            valueDate,
            normalizedPayload
        );

        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("RTGS_ADAPTER");

        return txn;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.RTGS;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Extracts a value from a simple flat JSON string by field name.
     *
     * Works for: {"key":"value"} format only.
     * Does NOT handle nested JSON, arrays, or numbers without quotes.
     * For production, replace with Jackson ObjectMapper.
     *
     * @param json      The JSON string to parse
     * @param fieldName The key whose value you want
     * @return          The string value, or null if not found
     */
    private String extractJsonField(String json, String fieldName) {
        // Look for: "fieldName":"value"  or  "fieldName":value
        String searchKey = "\"" + fieldName + "\":";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) {
            return null;
        }

        int valueStart = keyIndex + searchKey.length();
        boolean isQuoted = json.charAt(valueStart) == '"';

        if (isQuoted) {
            // Value is a quoted string — find content between quotes
            int openQuote  = valueStart;
            int closeQuote = json.indexOf('"', openQuote + 1);
            return json.substring(openQuote + 1, closeQuote);
        } else {
            // Value is a number — find until comma or closing brace
            int endIndex = json.indexOf(',', valueStart);
            if (endIndex == -1) {
                endIndex = json.indexOf('}', valueStart);
            }
            return json.substring(valueStart, endIndex).trim();
        }
    }

    private String buildNormalizedPayload(String sourceRef, TransactionType txnType,
                                           BigDecimal amount, String currency,
                                           LocalDate valueDate) {
        return "{" +
               "\"source\":\"RTGS\"," +
               "\"sourceRef\":\"" + sourceRef + "\"," +
               "\"txnType\":\"" + txnType.name() + "\"," +
               "\"amount\":" + amount + "," +
               "\"currency\":\"" + currency + "\"," +
               "\"valueDate\":\"" + valueDate + "\"" +
               "}";
    }
}
