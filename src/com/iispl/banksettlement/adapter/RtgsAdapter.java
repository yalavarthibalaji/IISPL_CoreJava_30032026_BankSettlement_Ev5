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
 * RTGS sends transactions as JSON via REST API webhooks.
 * Each line in rtgs.json is one standalone JSON object.
 *
 * EXPECTED RAW PAYLOAD FORMAT (one JSON object per line in rtgs.json):
 * {
 *   "rtgsRef"      : "RTGS20240615001",
 *   "txnType"      : "CREDIT",
 *   "amount"       : "500000.00",
 *   "currency"     : "INR",
 *   "valueDate"    : "2024-06-15",
 *   "debitAccount" : "NOSTRO-001",
 *   "creditAccount": "ACC002"
 * }
 *
 * NOTE: Manual JSON parsing — no external library needed for core Java training.
 */
public class RtgsAdapter implements TransactionAdapter {

    private SourceSystem rtgsSourceSystem;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public RtgsAdapter() {
        this.rtgsSourceSystem = new SourceSystem(
            "RTGS",
            ProtocolType.REST_API,
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

    @Override
    public IncomingTransaction adapt(String rawPayload) {

        if (rawPayload == null || rawPayload.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "RtgsAdapter: rawPayload cannot be null or empty"
            );
        }

        // Skip comment lines
        if (rawPayload.trim().startsWith("#")) {
            throw new IllegalArgumentException("RtgsAdapter: Skipping comment line");
        }

        String sourceRef       = extractJsonField(rawPayload, "rtgsRef");
        String txnTypeStr      = extractJsonField(rawPayload, "txnType");
        String amountStr       = extractJsonField(rawPayload, "amount");
        String currency        = extractJsonField(rawPayload, "currency");
        String valueDateStr    = extractJsonField(rawPayload, "valueDate");
        String debitAccountNum = extractJsonField(rawPayload, "debitAccount");
        String creditAccountNum= extractJsonField(rawPayload, "creditAccount");

        if (sourceRef == null || txnTypeStr == null || amountStr == null
                || currency == null || valueDateStr == null
                || debitAccountNum == null || creditAccountNum == null) {
            throw new IllegalArgumentException(
                "RtgsAdapter: Missing required fields. Expected: rtgsRef, txnType, amount, " +
                "currency, valueDate, debitAccount, creditAccount. Got: " + rawPayload
            );
        }

        BigDecimal amount      = new BigDecimal(amountStr);
        LocalDate  valueDate   = LocalDate.parse(valueDateStr);
        TransactionType txnType = TransactionType.valueOf(txnTypeStr.toUpperCase());

        // RTGS minimum amount validation — must be >= 2,00,000 INR
        if ("INR".equalsIgnoreCase(currency) && amount.compareTo(new BigDecimal("200000")) < 0) {
            throw new IllegalArgumentException(
                "RtgsAdapter: RTGS transactions must be minimum Rs. 2,00,000. Got: " + amount
            );
        }

        String normalizedPayload = buildNormalizedPayload(
            sourceRef, txnType, amount, currency, valueDate,
            debitAccountNum, creditAccountNum
        );

        IncomingTransaction txn = new IncomingTransaction(
            rtgsSourceSystem, sourceRef, rawPayload,
            txnType, amount, currency, valueDate, normalizedPayload
        );

        txn.setDebitAccountNumber(debitAccountNum);
        txn.setCreditAccountNumber(creditAccountNum);
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

    private String extractJsonField(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\":";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return null;

        int valueStart = keyIndex + searchKey.length();

        // Skip whitespace after colon
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

    private String buildNormalizedPayload(String sourceRef, TransactionType txnType,
                                           BigDecimal amount, String currency,
                                           LocalDate valueDate,
                                           String debitAcc, String creditAcc) {
        return "{" +
               "\"source\":\"RTGS\"," +
               "\"sourceRef\":\"" + sourceRef + "\"," +
               "\"txnType\":\"" + txnType.name() + "\"," +
               "\"amount\":" + amount + "," +
               "\"currency\":\"" + currency + "\"," +
               "\"valueDate\":\"" + valueDate + "\"," +
               "\"debitAccount\":\"" + debitAcc + "\"," +
               "\"creditAccount\":\"" + creditAcc + "\"" +
               "}";
    }
}