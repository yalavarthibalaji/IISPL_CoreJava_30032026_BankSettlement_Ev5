package com.iispl.banksettlement.adapter;

import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.entity.SourceSystem;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.enums.ProtocolType;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * CbsAdapter — Adapter for the Core Banking System (CBS).
 *
 * CBS sends transactions as pipe-delimited plain text flat files.
 *
 * RAW PAYLOAD FORMAT (10 pipe-separated fields):
 * ─────────────────────────────────────────────────────────────
 *  Field         Position   Example Value
 * ─────────────────────────────────────────────────────────────
 *  CBS_TXN_ID    [0]        001
 *  ACCT_DR       [1]        SB10023456       ← debit account
 *  ACCT_CR       [2]        SB10078901       ← credit account
 *  AMT           [3]        25000.00
 *  CCY           [4]        INR
 *  TXN_DT        [5]        20260402         ← format: yyyyMMdd (Indian)
 *  TXN_TYPE      [6]        CREDIT / DEBIT
 *  NARRATION     [7]        Salary credit March 2026
 *  BRANCH_CODE   [8]        BLR001
 *  MAKER_ID      [9]        EMP4521
 * ─────────────────────────────────────────────────────────────
 *
 * EXAMPLE LINE:
 *   001|SB10023456|SB10078901|25000.00|INR|20260402|CREDIT|Salary credit March 2026|BLR001|EMP4521
 *
 * DATE FORMAT NOTE:
 *   CBS sends TXN_DT in yyyyMMdd format (e.g. 20260402 = 2nd April 2026).
 *   This adapter parses it using DateTimeFormatter.ofPattern("yyyyMMdd").
 *
 * NORMALIZED PAYLOAD NOTE:
 *   Core fields (sourceRef, txnType, amount, currency, valueDate,
 *   debitAccount, creditAccount) are the canonical fields.
 *   Extra CBS-specific fields (NARRATION, BRANCH_CODE, MAKER_ID)
 *   are stored inside normalizedPayload JSON for audit purposes.
 */
public class CbsAdapter implements TransactionAdapter {

    // Date format CBS uses: yyyyMMdd (e.g. 20260402)
    private static final DateTimeFormatter CBS_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SourceSystem cbsSourceSystem;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public CbsAdapter() {
        this.cbsSourceSystem = new SourceSystem(
            "CBS",
            ProtocolType.FLAT_FILE,
            "{\"protocol\":\"PIPE_DELIMITED\",\"fileDir\":\"/cbs/inbound\"}",
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

        // CBS sends exactly 10 pipe-separated fields
        if (parts.length < 10) {
            throw new IllegalArgumentException(
                "CbsAdapter: Invalid CBS payload. Expected 10 pipe-separated fields: " +
                "CBS_TXN_ID|ACCT_DR|ACCT_CR|AMT|CCY|TXN_DT|TXN_TYPE|NARRATION|BRANCH_CODE|MAKER_ID" +
                ". Got: " + rawPayload
            );
        }

        // ---- Parse all 10 fields ----
        String sourceRef        = parts[0].trim();   // CBS_TXN_ID
        String debitAccountNum  = parts[1].trim();   // ACCT_DR
        String creditAccountNum = parts[2].trim();   // ACCT_CR
        BigDecimal amount       = new BigDecimal(parts[3].trim()); // AMT
        String currency         = parts[4].trim();   // CCY
        String txnDtStr         = parts[5].trim();   // TXN_DT — yyyyMMdd format
        String txnTypeStr       = parts[6].trim();   // TXN_TYPE — CREDIT or DEBIT
        String narration        = parts[7].trim();   // NARRATION — extra field
        String branchCode       = parts[8].trim();   // BRANCH_CODE — extra field
        String makerId          = parts[9].trim();   // MAKER_ID — extra field

        // Parse date: CBS sends yyyyMMdd (Indian format), convert to LocalDate
        LocalDate valueDate = LocalDate.parse(txnDtStr, CBS_DATE_FORMAT);

        // Map txnType — CBS sends CREDIT or DEBIT (enum-compatible directly)
        TransactionType txnType = TransactionType.valueOf(txnTypeStr.toUpperCase());

        // Build normalizedPayload — canonical fields + CBS-specific extra fields
        String normalizedPayload = buildNormalizedPayload(
            sourceRef, txnType, amount, currency, valueDate,
            debitAccountNum, creditAccountNum,
            narration, branchCode, makerId
        );

        IncomingTransaction txn = new IncomingTransaction(
            cbsSourceSystem, sourceRef, rawPayload,
            txnType, amount, currency, valueDate, normalizedPayload
        );

        txn.setDebitAccountNumber(debitAccountNum);
        txn.setCreditAccountNumber(creditAccountNum);
        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("CBS_ADAPTER");
        // requiresAccountValidation stays true (default) — CBS sends real account numbers

        return txn;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.CBS;
    }

    // -----------------------------------------------------------------------
    // Private helper — builds normalizedPayload JSON string
    // Canonical fields + CBS-specific extra fields all in one JSON
    // -----------------------------------------------------------------------

    private String buildNormalizedPayload(String sourceRef, TransactionType txnType,
                                          BigDecimal amount, String currency,
                                          LocalDate valueDate,
                                          String debitAcc, String creditAcc,
                                          String narration, String branchCode,
                                          String makerId) {
        return "{"
            + "\"source\":\"CBS\","
            + "\"sourceRef\":\"" + sourceRef + "\","
            + "\"txnType\":\"" + txnType.name() + "\","
            + "\"amount\":" + amount + ","
            + "\"currency\":\"" + currency + "\","
            + "\"valueDate\":\"" + valueDate + "\","
            + "\"debitAccount\":\"" + debitAcc + "\","
            + "\"creditAccount\":\"" + creditAcc + "\","
            + "\"narration\":\"" + narration + "\","
            + "\"branchCode\":\"" + branchCode + "\","
            + "\"makerId\":\"" + makerId + "\""
            + "}";
    }
}