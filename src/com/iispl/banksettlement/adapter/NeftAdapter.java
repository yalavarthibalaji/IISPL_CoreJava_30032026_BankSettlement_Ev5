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
 * NeftAdapter — Adapter for NEFT (National Electronic Funds Transfer) transactions.
 *
 * NEFT sends batch files in fixed-width text format.
 * Each record is now 172 characters wide (was 132 in v2).
 *
 * RAW PAYLOAD FORMAT (Fixed-Width — 12 fields, v3 adds FROM_BANK, TO_BANK):
 * ──────────────────────────────────────────────────────────────────────────
 *  Field          Position    Length   Example Value
 * ──────────────────────────────────────────────────────────────────────────
 *  RECORD_TYPE    001-002     2        CR           ← CR=CREDIT, DR=DEBIT
 *  NEFT_REF       003-018     16       NEFT260402001   ← sourceRef; date at 5-10
 *  SENDER_IFSC    019-029     11       ICIC0002345
 *  SENDER_ACCT    030-049     20       NEFT-ACC-DR-001 ← debitAccount
 *  BENE_IFSC      050-060     11       SBIN0001234
 *  BENE_ACCT      061-080     20       NEFT-ACC-CR-001 ← creditAccount
 *  BENE_NAME      081-110     30       RAMESH KUMAR
 *  AMT            111-122     12       000010000.00
 *  PURPOSE_CODE   123-126     4        OTHR
 *  BATCH_NO       127-132     6        B00012
 *  FROM_BANK      133-152     20       ICICI Bank      ← NEW (v3) — fromBank
 *  TO_BANK        153-172     20       SBI Bank        ← NEW (v3) — toBank
 * ──────────────────────────────────────────────────────────────────────────
 *
 * NOTE: Positions above are 1-based. Java substring() uses 0-based:
 *   Position 133-152 (1-based) → substring(132, 152) (0-based)
 *   Position 153-172 (1-based) → substring(152, 172) (0-based)
 *
 * DATE EXTRACTION FROM NEFT_REF:
 *   "NEFT260402001" → index 4-9 = "260402" (yyMMdd) → 2026-04-02
 *
 * CHANGE LOG (v3 — fromBank / toBank):
 *   - Record length extended from 132 → 172 chars.
 *   - FROM_BANK at substring(132, 152) — 20 chars, space-padded.
 *   - TO_BANK   at substring(152, 172) — 20 chars, space-padded.
 *   - Both are trimmed and stored in txn.fromBank / txn.toBank and normalizedPayload.
 *   - neft_transactions.txt updated with FROM_BANK and TO_BANK appended.
 *   - TxtFileReader minimum length check updated to 172.
 */
public class NeftAdapter implements TransactionAdapter {

    // Updated minimum length — original 132 + 20 (FROM_BANK) + 20 (TO_BANK) = 172
    private static final int NEFT_RECORD_MIN_LENGTH = 172;

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

        if (rawPayload.trim().startsWith("#")) {
            throw new IllegalArgumentException("NeftAdapter: Skipping comment line");
        }

        if (rawPayload.length() < NEFT_RECORD_MIN_LENGTH) {
            throw new IllegalArgumentException(
                "NeftAdapter: NEFT record must be at least " + NEFT_RECORD_MIN_LENGTH +
                " characters (includes FROM_BANK and TO_BANK fields). " +
                "Got length: " + rawPayload.length() + " | Record: " + rawPayload
            );
        }

        // ---- Extract all fields using fixed-width positions (0-based) ----

        // Original fields (0-131) — unchanged from v2
        String recordType  = rawPayload.substring(0, 2).trim();    // CR or DR
        String neftRef     = rawPayload.substring(2, 18).trim();   // e.g. NEFT260402001
        String senderIFSC  = rawPayload.substring(18, 29).trim();  // e.g. ICIC0002345
        String senderAcct  = rawPayload.substring(29, 49).trim();  // debit account
        String beneIFSC    = rawPayload.substring(49, 60).trim();  // e.g. SBIN0001234
        String beneAcct    = rawPayload.substring(60, 80).trim();  // credit account
        String beneName    = rawPayload.substring(80, 110).trim(); // beneficiary name
        String amtStr      = rawPayload.substring(110, 122).trim();// e.g. 000010000.00
        String purposeCode = rawPayload.substring(122, 126).trim();// e.g. OTHR
        String batchNo     = rawPayload.substring(126, 132).trim();// e.g. B00012

        // NEW fields (132-171) — added in v3
        String fromBank    = rawPayload.substring(132, 152).trim();// e.g. ICICI Bank
        String toBank      = rawPayload.substring(152, 172).trim();// e.g. SBI Bank

        // ---- Validate required fields ----
        if (recordType.isEmpty() || neftRef.isEmpty() || senderAcct.isEmpty()
                || beneAcct.isEmpty() || amtStr.isEmpty()) {
            throw new IllegalArgumentException(
                "NeftAdapter: Missing required fields. " +
                "RECORD_TYPE, NEFT_REF, SENDER_ACCT, BENE_ACCT, AMT must not be blank."
            );
        }

        if (fromBank.isEmpty()) {
            throw new IllegalArgumentException(
                "NeftAdapter: FROM_BANK (positions 133-152) is blank. " +
                "All NEFT records must include FROM_BANK and TO_BANK."
            );
        }

        if (toBank.isEmpty()) {
            throw new IllegalArgumentException(
                "NeftAdapter: TO_BANK (positions 153-172) is blank. " +
                "All NEFT records must include FROM_BANK and TO_BANK."
            );
        }

        // ---- Map RECORD_TYPE → TransactionType ----
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
                    "NeftAdapter: Unknown RECORD_TYPE '" + recordType + "'. Expected CR or DR."
                );
        }

        BigDecimal amount = new BigDecimal(amtStr);

        // NEFT is always INR — stored in normalizedPayload for audit
        String currency = "INR";

        // Extract valueDate from NEFT_REF (index 4-9 = yyMMdd)
        LocalDate valueDate = extractValueDateFromNeftRef(neftRef);

        // ---- Build normalizedPayload — includes fromBank and toBank ----
        String normalizedPayload = buildNormalizedPayload(
            neftRef, txnType, amount, currency, valueDate,
            senderAcct, beneAcct,
            senderIFSC, beneIFSC, beneName, purposeCode, batchNo,
            fromBank, toBank
        );

        // ---- Build IncomingTransaction ----
        IncomingTransaction txn = new IncomingTransaction(
            neftSourceSystem, neftRef, rawPayload,
            txnType, amount, valueDate, normalizedPayload
        );

        txn.setFromBank(fromBank);
        txn.setToBank(toBank);
        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("NEFT_ADAPTER");

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
     * Extracts valueDate from NEFT_REF.
     * "NEFT260402001" → index 4-9 = "260402" (yyMMdd) → 2026-04-02
     */
    private LocalDate extractValueDateFromNeftRef(String neftRef) {
        if (neftRef.length() < 10) {
            throw new IllegalArgumentException(
                "NeftAdapter: NEFT_REF too short. Expected at least 10 chars. Got: " + neftRef
            );
        }
        String yymmdd = neftRef.substring(4, 10); // e.g. "260402"
        int year  = 2000 + Integer.parseInt(yymmdd.substring(0, 2));
        int month = Integer.parseInt(yymmdd.substring(2, 4));
        int day   = Integer.parseInt(yymmdd.substring(4, 6));
        return LocalDate.of(year, month, day);
    }

    private String buildNormalizedPayload(String sourceRef, TransactionType txnType,
                                          BigDecimal amount, String currency,
                                          LocalDate valueDate,
                                          String debitAcc, String creditAcc,
                                          String senderIFSC, String beneIFSC,
                                          String beneName, String purposeCode,
                                          String batchNo,
                                          String fromBank, String toBank) {
        return "{"
            + "\"source\":\"NEFT\","
            + "\"sourceRef\":\"" + sourceRef + "\","
            + "\"txnType\":\"" + txnType.name() + "\","
            + "\"amount\":" + amount + ","
            + "\"currency\":\"" + currency + "\","
            + "\"valueDate\":\"" + valueDate + "\","
            + "\"debitAccount\":\"" + debitAcc + "\","
            + "\"creditAccount\":\"" + creditAcc + "\","
            + "\"fromBank\":\"" + fromBank + "\","
            + "\"toBank\":\"" + toBank + "\","
            + "\"senderIFSC\":\"" + nullSafe(senderIFSC) + "\","
            + "\"beneIFSC\":\"" + nullSafe(beneIFSC) + "\","
            + "\"beneName\":\"" + nullSafe(beneName) + "\","
            + "\"purposeCode\":\"" + nullSafe(purposeCode) + "\","
            + "\"batchNo\":\"" + nullSafe(batchNo) + "\""
            + "}";
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}