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
 * CbsAdapter — Adapter for the Core Banking System (CBS).
 *
 * CBS sends transactions as pipe-delimited plain text strings.
 *
 * EXPECTED RAW PAYLOAD FORMAT (one line per transaction in cbs.txt):
 *   sourceRef|txnType|amount|currency|valueDate|debitAccount|creditAccount
 *
 * EXAMPLE:
 *   CBS-REF-001|CREDIT|50000.00|INR|2024-06-15|ACC001|ACC002
 *
 *   [0] sourceRef      — CBS reference number
 *   [1] txnType        — CREDIT / DEBIT / REVERSAL
 *   [2] amount         — transaction amount
 *   [3] currency       — INR / USD etc.
 *   [4] valueDate      — yyyy-MM-dd
 *   [5] debitAccount   — account number being debited
 *   [6] creditAccount  — account number being credited
 */
public class CbsAdapter implements TransactionAdapter {

    private SourceSystem cbsSourceSystem;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public CbsAdapter() {
        this.cbsSourceSystem = new SourceSystem(
            "CBS",
            ProtocolType.FLAT_FILE,
            "{\"protocol\":\"PIPE_DELIMITED\"}",
            true,
            "cbs-support@bank.com"
        );
        this.cbsSourceSystem.setSourceSystemId(1L);
        this.cbsSourceSystem.setCreatedBy("SYSTEM");
    }

    // -----------------------------------------------------------------------
    // TransactionAdapter implementation
    // -----------------------------------------------------------------------

    @Override
    public IncomingTransaction adapt(String rawPayload) {

        if (rawPayload == null || rawPayload.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "CbsAdapter: rawPayload cannot be null or empty"
            );
        }

        // Skip comment lines (lines starting with #)
        if (rawPayload.trim().startsWith("#")) {
            throw new IllegalArgumentException(
                "CbsAdapter: Skipping comment line"
            );
        }

        String[] parts = rawPayload.split("\\|");

        // Now requires 7 fields (added debitAccount and creditAccount)
        if (parts.length < 7) {
            throw new IllegalArgumentException(
                "CbsAdapter: Invalid CBS payload. Expected 7 pipe-separated fields: " +
                "sourceRef|txnType|amount|currency|valueDate|debitAccount|creditAccount. Got: " + rawPayload
            );
        }

        String sourceRef         = parts[0].trim();
        String txnTypeStr        = parts[1].trim();
        BigDecimal amount        = new BigDecimal(parts[2].trim());
        String currency          = parts[3].trim();
        LocalDate valueDate      = LocalDate.parse(parts[4].trim());
        String debitAccountNum   = parts[5].trim();
        String creditAccountNum  = parts[6].trim();

        TransactionType txnType = TransactionType.valueOf(txnTypeStr.toUpperCase());

        String normalizedPayload = buildNormalizedPayload(
            sourceRef, txnType, amount, currency, valueDate,
            debitAccountNum, creditAccountNum
        );

        IncomingTransaction txn = new IncomingTransaction(
            cbsSourceSystem, sourceRef, rawPayload,
            txnType, amount, currency, valueDate, normalizedPayload
        );

        txn.setDebitAccountNumber(debitAccountNum);
        txn.setCreditAccountNumber(creditAccountNum);
        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("CBS_ADAPTER");

        return txn;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.CBS;
    }

    // -----------------------------------------------------------------------
    // Private helper
    // -----------------------------------------------------------------------

    private String buildNormalizedPayload(String sourceRef, TransactionType txnType,
                                           BigDecimal amount, String currency,
                                           LocalDate valueDate,
                                           String debitAcc, String creditAcc) {
        return "{" +
               "\"source\":\"CBS\"," +
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