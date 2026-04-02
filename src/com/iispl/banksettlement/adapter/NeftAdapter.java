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
 * NeftAdapter — Adapter for NEFT (National Electronic Funds Transfer) transactions.
 *
 * NEFT sends batch files with multiple records in fixed-width text format.
 * Each record is exactly 132 characters wide.
 *
 * RAW PAYLOAD FORMAT (Fixed-Width — 10 fields):
 * ──────────────────────────────────────────────────────────────
 *  Field          Position   Length   Example Value
 * ──────────────────────────────────────────────────────────────
 *  RECORD_TYPE    01-02      2        CR           ← CR=CREDIT, DR=DEBIT
 *  NEFT_REF       03-18      16       NEFT260402001← sourceRef; date at pos 5-10
 *  SENDER_IFSC    19-29      11       ICIC0002345  ← extra field
 *  SENDER_ACCT    30-49      20       CA00123456789012  ← debit account
 *  BENE_IFSC      50-60      11       SBIN0001234  ← extra field
 *  BENE_ACCT      61-80      20       SB00987654321098  ← credit account
 *  BENE_NAME      81-110     30       RAMESH KUMAR ← extra field
 *  AMT            111-122    12       000010000.00
 *  PURPOSE_CODE   123-126    4        OTHR         ← extra field
 *  BATCH_NO       127-132    6        B00012       ← extra field
 * ──────────────────────────────────────────────────────────────
 *
 * NOTE: Positions above are 1-based. In Java substring() they become 0-based:
 *   position 01-02  → substring(0, 2)
 *   position 03-18  → substring(2, 18)
 *   ...and so on.
 *
 * DATE EXTRACTION FROM NEFT_REF:
 *   NEFT_REF format is "NEFT" + YYMMDD + sequence (e.g. "NEFT260402001")
 *   Date portion is at characters 5-10 (0-based: index 4-9) = "260402"
 *   Format is yyMMdd → 260402 = 2nd April 2026
 *   Parsed as: year = 2000 + 26 = 2026, month = 04, day = 02
 *
 * TXNTYPE MAPPING:
 *   RECORD_TYPE "CR" → TransactionType.CREDIT
 *   RECORD_TYPE "DR" → TransactionType.DEBIT
 *
 * CANONICAL FIELDS → IncomingTransaction fields:
 *   NEFT_REF    → sourceRef
 *   SENDER_ACCT → debitAccountNumber
 *   BENE_ACCT   → creditAccountNumber
 *   AMT         → amount  (strip leading zeros, parse as BigDecimal)
 *   (fixed INR) → currency
 *   (from ref)  → valueDate (extracted from NEFT_REF positions 5-10)
 *   RECORD_TYPE → txnType (CR→CREDIT, DR→DEBIT)
 *
 * EXTRA FIELDS → normalizedPayload JSON:
 *   SENDER_IFSC, BENE_IFSC, BENE_NAME, PURPOSE_CODE, BATCH_NO
 */
public class NeftAdapter implements TransactionAdapter {

    private final SourceSystem neftSourceSystem;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public NeftAdapter() {
        this.neftSourceSystem = new SourceSystem(
            "NEFT",
            ProtocolType.FLAT_FILE,
            "{\"protocol\":\"FIXED_WIDTH\",\"fileDir\":\"/neft/inbound\",\"batchSize\":\"1000\"}",
            true,
            "neft-ops@npci.org.in"
        );
        this.neftSourceSystem.setSourceSystemId(4L);
        this.neftSourceSystem.setCreatedBy("SYSTEM");
    }

    // -----------------------------------------------------------------------
    // TransactionAdapter implementation
    // -----------------------------------------------------------------------

    @Override
    public IncomingTransaction adapt(String rawPayload) {

        if (rawPayload == null || rawPayload.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "NeftAdapter: rawPayload cannot be null or empty"
            );
        }

        // Skip header/comment lines
        if (rawPayload.trim().startsWith("#")) {
            throw new IllegalArgumentException(
                "NeftAdapter: Skipping comment line"
            );
        }

        // NEFT fixed-width record must be at least 132 characters
        if (rawPayload.length() < 132) {
            throw new IllegalArgumentException(
                "NeftAdapter: NEFT fixed-width record must be at least 132 characters. " +
                "Got length: " + rawPayload.length() + " | Record: " + rawPayload
            );
        }

        // ---- Extract fields using fixed-width positions (0-based) ----
        // Position 01-02  → index 0-2
        String recordType  = rawPayload.substring(0, 2).trim();   // CR or DR
        // Position 03-18  → index 2-18
        String neftRef     = rawPayload.substring(2, 18).trim();  // e.g. NEFT260402001
        // Position 19-29  → index 18-29
        String senderIFSC  = rawPayload.substring(18, 29).trim(); // e.g. ICIC0002345
        // Position 30-49  → index 29-49
        String senderAcct  = rawPayload.substring(29, 49).trim(); // debit account
        // Position 50-60  → index 49-60
        String beneIFSC    = rawPayload.substring(49, 60).trim(); // e.g. SBIN0001234
        // Position 61-80  → index 60-80
        String beneAcct    = rawPayload.substring(60, 80).trim(); // credit account
        // Position 81-110 → index 80-110
        String beneName    = rawPayload.substring(80, 110).trim();// beneficiary name
        // Position 111-122→ index 110-122
        String amtStr      = rawPayload.substring(110, 122).trim();// e.g. 000010000.00
        // Position 123-126→ index 122-126
        String purposeCode = rawPayload.substring(122, 126).trim();// e.g. OTHR
        // Position 127-132→ index 126-132
        String batchNo     = rawPayload.substring(126, 132).trim();// e.g. B00012

        // ---- Validate required fields ----
        if (recordType.isEmpty() || neftRef.isEmpty() || senderAcct.isEmpty()
                || beneAcct.isEmpty() || amtStr.isEmpty()) {
            throw new IllegalArgumentException(
                "NeftAdapter: Missing required fields in NEFT record. " +
                "RECORD_TYPE, NEFT_REF, SENDER_ACCT, BENE_ACCT, AMT must not be blank. " +
                "Got: " + rawPayload
            );
        }

        // ---- Map RECORD_TYPE → TransactionType ----
        // CR = Credit (money coming in to beneficiary)
        // DR = Debit  (money going out from sender)
        TransactionType txnType;
        switch (recordType.toUpperCase()) {
            case "CR":
                txnType = TransactionType.CREDIT;
                break;
            case "DR":
                txnType = TransactionType.DEBIT;
                break;
            default:
                throw new IllegalArgumentException(
                    "NeftAdapter: Unknown RECORD_TYPE '" + recordType +
                    "'. Expected CR or DR."
                );
        }

        // ---- Parse amount — strip leading zeros, e.g. "000010000.00" → 10000.00 ----
        BigDecimal amount = new BigDecimal(amtStr);

        // NEFT is always INR (domestic Indian transfer)
        String currency = "INR";

        // ---- Extract valueDate from NEFT_REF ----
        // NEFT_REF format: "NEFT" + yyMMdd + sequence
        // e.g. "NEFT260402001" → date portion at index 4-9 = "260402" (yyMMdd)
        LocalDate valueDate = extractValueDateFromNeftRef(neftRef);

        // ---- Build normalizedPayload — canonical + extra NEFT fields ----
        String normalizedPayload = buildNormalizedPayload(
            neftRef, txnType, amount, currency, valueDate,
            senderAcct, beneAcct,
            senderIFSC, beneIFSC, beneName, purposeCode, batchNo
        );

        IncomingTransaction txn = new IncomingTransaction(
            neftSourceSystem, neftRef, rawPayload,
            txnType, amount, currency, valueDate, normalizedPayload
        );

        txn.setDebitAccountNumber(senderAcct);
        txn.setCreditAccountNumber(beneAcct);
        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("NEFT_ADAPTER");
        // requiresAccountValidation stays true (default) — NEFT sends real account numbers

        return txn;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.NEFT;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Extracts the value date from NEFT_REF.
     *
     * NEFT_REF format: "NEFT" + yyMMdd + sequence
     * Example: "NEFT260402001"
     *   → date at index 4-9 (inclusive) = "260402"
     *   → yyMMdd: yy=26 (2026), MM=04 (April), dd=02
     *   → LocalDate = 2026-04-02
     *
     * @param neftRef The NEFT_REF string from the fixed-width record
     * @return        LocalDate parsed from the embedded date
     */
    private LocalDate extractValueDateFromNeftRef(String neftRef) {
        // NEFT_REF must be at least 10 chars: "NEFT" (4) + "yyMMdd" (6)
        if (neftRef.length() < 10) {
            throw new IllegalArgumentException(
                "NeftAdapter: NEFT_REF too short to extract date. " +
                "Expected at least 10 chars (NEFT + yyMMdd + ...). Got: " + neftRef
            );
        }

        // Extract 6-char date portion starting at index 4 (after "NEFT")
        String yymmdd = neftRef.substring(4, 10); // e.g. "260402"

        int year  = 2000 + Integer.parseInt(yymmdd.substring(0, 2)); // 2026
        int month = Integer.parseInt(yymmdd.substring(2, 4));         // 04
        int day   = Integer.parseInt(yymmdd.substring(4, 6));         // 02

        return LocalDate.of(year, month, day);
    }

    /**
     * Builds the normalizedPayload JSON string.
     * Contains all canonical fields + all NEFT-specific extra fields.
     */
    private String buildNormalizedPayload(String sourceRef, TransactionType txnType,
                                          BigDecimal amount, String currency,
                                          LocalDate valueDate,
                                          String debitAcc, String creditAcc,
                                          String senderIFSC, String beneIFSC,
                                          String beneName, String purposeCode,
                                          String batchNo) {
        return "{"
            + "\"source\":\"NEFT\","
            + "\"sourceRef\":\"" + sourceRef + "\","
            + "\"txnType\":\"" + txnType.name() + "\","
            + "\"amount\":" + amount + ","
            + "\"currency\":\"" + currency + "\","
            + "\"valueDate\":\"" + valueDate + "\","
            + "\"debitAccount\":\"" + debitAcc + "\","
            + "\"creditAccount\":\"" + creditAcc + "\","
            + "\"senderIFSC\":\"" + nullSafe(senderIFSC) + "\","
            + "\"beneIFSC\":\"" + nullSafe(beneIFSC) + "\","
            + "\"beneName\":\"" + nullSafe(beneName) + "\","
            + "\"purposeCode\":\"" + nullSafe(purposeCode) + "\","
            + "\"batchNo\":\"" + nullSafe(batchNo) + "\""
            + "}";
    }

    /** Returns the value if not null, otherwise returns empty string. */
    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}