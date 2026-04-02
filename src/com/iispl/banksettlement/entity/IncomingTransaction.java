package com.iispl.banksettlement.entity;

import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * IncomingTransaction — The CANONICAL (standardised) form of every transaction,
 * regardless of which source system it came from.
 *
 * WHY THIS EXISTS:
 *   CBS sends pipe-delimited flat files, RTGS sends XML over MQ,
 *   SWIFT sends MT103 messages, NEFT sends fixed-width batch files,
 *   UPI sends JSON webhooks, Fintech sends proprietary JSON —
 *   each format is completely different. The adapter classes convert
 *   each raw format into this single standard class.
 *   After adaptation, all downstream processing works only with IncomingTransaction.
 *
 * FLOW:
 *   Raw Payload → Adapter → IncomingTransaction → BlockingQueue → Settlement Engine
 *
 * HAS-A relationship: contains a SourceSystem object to know its origin.
 * Extends BaseEntity: inherits id, createdAt, updatedAt, createdBy, version.
 *
 * NEW FIELD — requiresAccountValidation:
 *   Most source systems send actual bank account numbers that can be
 *   validated against the database. UPI is an exception — it sends
 *   VPA addresses (e.g. ramesh@okicici) instead of account numbers,
 *   so account/customer DB validation is skipped for UPI transactions.
 *   All other source systems have this flag set to true by default.
 */
public class IncomingTransaction extends BaseEntity {

    private static final long serialVersionUID = 1L;

    // Unique identifier for this incoming transaction record
    private Long incomingTxnId;

    // HAS-A: which source system sent this transaction
    // (CBS, RTGS, SWIFT, NEFT, UPI, FINTECH)
    private SourceSystem sourceSystem;

    // The original reference number from the source system
    // CBS  → CBS_TXN_ID,  RTGS → MsgId,  SWIFT → :20: field
    // NEFT → NEFT_REF,    UPI  → upiTxnId, Fintech → ft_ref
    private String sourceRef;

    // The raw original payload as received — stored for audit/replay purposes
    // CBS=pipe-delimited, RTGS=XML, SWIFT=MT103, NEFT=fixed-width, UPI=JSON, Fintech=JSON
    private String rawPayload;

    // What kind of transaction is this? (CREDIT, DEBIT, REVERSAL etc.)
    private TransactionType txnType;

    // Transaction amount — using BigDecimal because financial amounts
    // must NEVER use double or float (precision loss causes money errors!)
    private BigDecimal amount;

    // ISO 4217 currency code e.g. "INR", "USD"
    private String currency;

    // The date on which this transaction should be settled/valued
    // All adapters normalise their date formats to LocalDate (dd-MM-yyyy internally)
    private LocalDate valueDate;

    // Current processing stage of this transaction in the pipeline
    private ProcessingStatus processingStatus;

    // Exact timestamp when this transaction was received by our system
    private LocalDateTime ingestTimestamp;

    // The cleaned/standardised payload after adapter processing
    // Always JSON format regardless of original source format.
    // All source-specific extra fields (NARRATION, RBIRefNo, BIC codes etc.)
    // are stored here as key-value pairs in JSON.
    private String normalizedPayload;

    // The debit account identifier parsed from the raw payload.
    // CBS  → ACCT_DR,        RTGS → SenderAcct
    // SWIFT→ :50K: account,  NEFT → SENDER_ACCT
    // UPI  → payerVpa (VPA address — not a bank account number)
    // Fintech → sender.account
    private String debitAccountNumber;

    // The credit account identifier parsed from the raw payload.
    // CBS  → ACCT_CR,        RTGS → ReceiverAcct
    // SWIFT→ :59: account,   NEFT → BENE_ACCT
    // UPI  → payeeVpa (VPA address — not a bank account number)
    // Fintech → receiver.account
    private String creditAccountNumber;

    // If this transaction failed validation or processing, the reason is stored here.
    // null if transaction is valid and successfully processed.
    private String failureReason;

    // Controls whether IngestionWorker should validate debit/credit accounts
    // and their linked customers against the database.
    //
    // TRUE  (default) — CBS, RTGS, SWIFT, NEFT, Fintech send real bank account
    //                   numbers that exist in the accounts table. Validate them.
    //
    // FALSE           — UPI sends VPA addresses (e.g. ramesh@okicici), NOT account
    //                   numbers. DB account/customer validation is skipped for UPI.
    private boolean requiresAccountValidation;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Default constructor.
     * Sets processingStatus to RECEIVED, ingestTimestamp to now,
     * and requiresAccountValidation to true (safe default for all non-UPI sources).
     */
    public IncomingTransaction() {
        super();
        this.processingStatus          = ProcessingStatus.RECEIVED;
        this.ingestTimestamp           = LocalDateTime.now();
        this.requiresAccountValidation = true; // default — all sources validate except UPI
    }

    /**
     * Parameterized constructor — used by adapter classes to build
     * a fully populated IncomingTransaction from a raw payload.
     *
     * requiresAccountValidation defaults to true here.
     * UPI adapter must call setRequiresAccountValidation(false) after using this constructor.
     *
     * @param sourceSystem      The source system that sent this transaction
     * @param sourceRef         Original reference from the source system
     * @param rawPayload        The raw payload as received
     * @param txnType           Type of transaction (CREDIT/DEBIT etc.)
     * @param amount            Transaction amount (BigDecimal — no float/double!)
     * @param currency          ISO currency code e.g. "INR"
     * @param valueDate         Settlement value date
     * @param normalizedPayload Cleaned JSON payload after normalisation
     */
    public IncomingTransaction(SourceSystem sourceSystem, String sourceRef,
                               String rawPayload, TransactionType txnType,
                               BigDecimal amount, String currency,
                               LocalDate valueDate, String normalizedPayload) {
        super();
        this.sourceSystem              = sourceSystem;
        this.sourceRef                 = sourceRef;
        this.rawPayload                = rawPayload;
        this.txnType                   = txnType;
        this.amount                    = amount;
        this.currency                  = currency;
        this.valueDate                 = valueDate;
        this.normalizedPayload         = normalizedPayload;
        this.processingStatus          = ProcessingStatus.RECEIVED;
        this.ingestTimestamp           = LocalDateTime.now();
        this.requiresAccountValidation = true; // default — UPI adapter overrides this to false
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public Long getIncomingTxnId() {
        return incomingTxnId;
    }

    public void setIncomingTxnId(Long incomingTxnId) {
        this.incomingTxnId = incomingTxnId;
    }

    public SourceSystem getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(SourceSystem sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public void setRawPayload(String rawPayload) {
        this.rawPayload = rawPayload;
    }

    public TransactionType getTxnType() {
        return txnType;
    }

    public void setTxnType(TransactionType txnType) {
        this.txnType = txnType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public void setValueDate(LocalDate valueDate) {
        this.valueDate = valueDate;
    }

    public ProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(ProcessingStatus processingStatus) {
        this.processingStatus = processingStatus;
    }

    public LocalDateTime getIngestTimestamp() {
        return ingestTimestamp;
    }

    public void setIngestTimestamp(LocalDateTime ingestTimestamp) {
        this.ingestTimestamp = ingestTimestamp;
    }

    public String getNormalizedPayload() {
        return normalizedPayload;
    }

    public void setNormalizedPayload(String normalizedPayload) {
        this.normalizedPayload = normalizedPayload;
    }

    public String getDebitAccountNumber() {
        return debitAccountNumber;
    }

    public void setDebitAccountNumber(String debitAccountNumber) {
        this.debitAccountNumber = debitAccountNumber;
    }

    public String getCreditAccountNumber() {
        return creditAccountNumber;
    }

    public void setCreditAccountNumber(String creditAccountNumber) {
        this.creditAccountNumber = creditAccountNumber;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public boolean isRequiresAccountValidation() {
        return requiresAccountValidation;
    }

    public void setRequiresAccountValidation(boolean requiresAccountValidation) {
        this.requiresAccountValidation = requiresAccountValidation;
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "IncomingTransaction{" +
               "incomingTxnId=" + incomingTxnId +
               ", sourceSystem=" + (sourceSystem != null ? sourceSystem.getSystemCode() : "null") +
               ", sourceRef='" + sourceRef + '\'' +
               ", txnType=" + txnType +
               ", amount=" + amount +
               ", currency='" + currency + '\'' +
               ", valueDate=" + valueDate +
               ", debitAccountNumber='" + debitAccountNumber + '\'' +
               ", creditAccountNumber='" + creditAccountNumber + '\'' +
               ", processingStatus=" + processingStatus +
               ", requiresAccountValidation=" + requiresAccountValidation +
               ", failureReason='" + failureReason + '\'' +
               ", ingestTimestamp=" + ingestTimestamp +
               '}';
    }
}