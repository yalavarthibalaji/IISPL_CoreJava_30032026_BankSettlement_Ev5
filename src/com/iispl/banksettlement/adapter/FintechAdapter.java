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
 * FintechAdapter — Adapter for third-party Fintech API transactions.
 *
 * Fintech partners (Razorpay, PayU etc.) send JSON via REST webhooks.
 * Each line in fintech.json is one standalone JSON object.
 *
 * EXPECTED RAW PAYLOAD FORMAT (one JSON object per line in fintech.json):
 * {
 *   "partnerRef"    : "RAZORPAY-TXN-001",
 *   "type"          : "CREDIT",
 *   "value"         : "12500.75",
 *   "ccy"           : "INR",
 *   "settlDate"     : "2024-06-15",
 *   "partnerCode"   : "RAZORPAY",
 *   "debitAccount"  : "ACC001",
 *   "creditAccount" : "ACC002"
 * }
 *
 * NOTE: Fintech uses different key names than our internal standard.
 *   "partnerRef"  → sourceRef
 *   "type"        → txnType
 *   "value"       → amount
 *   "ccy"         → currency
 *   "settlDate"   → valueDate
 */
public class FintechAdapter implements TransactionAdapter {

    private SourceSystem fintechSourceSystem;

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

        String sourceRef        = extractJsonField(rawPayload, "partnerRef");
        String txnTypeStr       = extractJsonField(rawPayload, "type");
        String amountStr        = extractJsonField(rawPayload, "value");
        String currency         = extractJsonField(rawPayload, "ccy");
        String valueDateStr     = extractJsonField(rawPayload, "settlDate");
        String partnerCode      = extractJsonField(rawPayload, "partnerCode");
        String debitAccountNum  = extractJsonField(rawPayload, "debitAccount");
        String creditAccountNum = extractJsonField(rawPayload, "creditAccount");

        if (sourceRef == null || txnTypeStr == null || amountStr == null
                || currency == null || valueDateStr == null
                || debitAccountNum == null || creditAccountNum == null) {
            throw new IllegalArgumentException(
                "FintechAdapter: Missing required fields. Expected: partnerRef, type, value, ccy, " +
                "settlDate, debitAccount, creditAccount. Got: " + rawPayload
            );
        }

        BigDecimal amount       = new BigDecimal(amountStr);
        LocalDate valueDate     = LocalDate.parse(valueDateStr);
        TransactionType txnType = TransactionType.valueOf(txnTypeStr.toUpperCase());

        String fullSourceRef = (partnerCode != null)
            ? "FINTECH-" + partnerCode + "-" + sourceRef
            : "FINTECH-" + sourceRef;

        String normalizedPayload = buildNormalizedPayload(
            fullSourceRef, partnerCode, txnType, amount, currency, valueDate,
            debitAccountNum, creditAccountNum
        );

        IncomingTransaction txn = new IncomingTransaction(
            fintechSourceSystem, fullSourceRef, rawPayload,
            txnType, amount, currency, valueDate, normalizedPayload
        );

        txn.setDebitAccountNumber(debitAccountNum);
        txn.setCreditAccountNumber(creditAccountNum);
        // Fintech transactions start as RECEIVED — need extra validation by settlement engine
        txn.setProcessingStatus(ProcessingStatus.RECEIVED);
        txn.setCreatedBy("FINTECH_ADAPTER");

        return txn;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.FINTECH;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

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

    private String buildNormalizedPayload(String sourceRef, String partnerCode,
                                           TransactionType txnType, BigDecimal amount,
                                           String currency, LocalDate valueDate,
                                           String debitAcc, String creditAcc) {
        return "{" +
               "\"source\":\"FINTECH\"," +
               "\"partnerCode\":\"" + (partnerCode != null ? partnerCode : "UNKNOWN") + "\"," +
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