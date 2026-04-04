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
 * RAW PAYLOAD FORMAT (10 pipe-separated fields, CCY field kept for format
 * compatibility but not stored as a separate entity field):
 * ─────────────────────────────────────────────────────────────
 *  Field         Position   Example Value
 * ─────────────────────────────────────────────────────────────
 *  CBS_TXN_ID    [0]        001
 *  ACCT_DR       [1]        SB10023456       ← debit account
 *  ACCT_CR       [2]        SB10078901       ← credit account
 *  AMT           [3]        25000.00
 *  CCY           [4]        INR              ← parsed but not stored as field
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
 * CHANGE LOG (v2):
 *   - currency removed from IncomingTransaction field (INR-only system).
 *     currency is still read from payload and stored in normalizedPayload for audit.
 *   - debitAccountNumber and creditAccountNumber are now stored ONLY inside
 *     normalizedPayload JSON as "debitAccount" and "creditAccount" keys.
 *     The settlement engine reads account numbers from normalizedPayload.
 *   - requiresAccountValidation removed — validation now happens at settlement phase.
 *   - IncomingTransaction constructor no longer takes currency parameter.
 */
public class CbsAdapter implements TransactionAdapter {

    private static final DateTimeFormatter CBS_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SourceSystem cbsSourceSystem;

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

    @Override
    public IncomingTransaction adapt(String rawPayload) {

        if (rawPayload == null || rawPayload.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "CbsAdapter: rawPayload cannot be null or empty"
            );
        }

        if (rawPayload.trim().startsWith("#")) {
            throw new IllegalArgumentException(
                "CbsAdapter: Skipping comment line"
            );
        }

        String[] parts = rawPayload.split("\\|");

        if (parts.length < 10) {
            throw new IllegalArgumentException(
                "CbsAdapter: Invalid CBS payload. Expected 10 pipe-separated fields: " +
                "CBS_TXN_ID|ACCT_DR|ACCT_CR|AMT|CCY|TXN_DT|TXN_TYPE|NARRATION|BRANCH_CODE|MAKER_ID" +
                ". Got: " + rawPayload
            );
        }

        String sourceRef        = parts[0].trim();
        String debitAccountNum  = parts[1].trim();
        String creditAccountNum = parts[2].trim();
        BigDecimal amount       = new BigDecimal(parts[3].trim());
        // currency read for normalizedPayload audit trail only — not stored as a field
        String currency         = parts[4].trim();
        String txnDtStr         = parts[5].trim();
        String txnTypeStr       = parts[6].trim();
        String narration        = parts[7].trim();
        String branchCode       = parts[8].trim();
        String makerId          = parts[9].trim();

        LocalDate valueDate     = LocalDate.parse(txnDtStr, CBS_DATE_FORMAT);
        TransactionType txnType = TransactionType.valueOf(txnTypeStr.toUpperCase());

        // Account numbers are embedded inside normalizedPayload as
        // "debitAccount" and "creditAccount" — the settlement engine reads them from here.
        String normalizedPayload = buildNormalizedPayload(
            sourceRef, txnType, amount, currency, valueDate,
            debitAccountNum, creditAccountNum,
            narration, branchCode, makerId
        );

        // IncomingTransaction constructor no longer accepts currency
        IncomingTransaction txn = new IncomingTransaction(
            cbsSourceSystem, sourceRef, rawPayload,
            txnType, amount, valueDate, normalizedPayload
        );

        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("CBS_ADAPTER");

        return txn;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.CBS;
    }

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