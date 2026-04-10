package com.iispl.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.iispl.enums.ProcessingStatus;
import com.iispl.enums.TransactionType;

/**
 * IncomingTransaction — The CANONICAL (standardised) form of every transaction,
 * regardless of which source system it came from.
 *
 * FLOW: Raw Payload → Adapter → IncomingTransaction → BlockingQueue →
 * Settlement Engine
 *
 * HAS-A relationship: contains a SourceSystem object to know its origin.
 * Extends BaseEntity: inherits id, createdAt, updatedAt, createdBy, version.
 *
 * CHANGE LOG (v3 — fromBank / toBank added):
 *
 * fromBank and toBank are now stored as BOTH: 1. Separate fields on
 * IncomingTransaction (for easy access in Java code) 2. Inside
 * normalizedPayload JSON as "fromBank" and "toBank" keys (for the settlement
 * engine and audit trail)
 *
 * WHY BOTH? Having them as fields lets IngestionWorker log and validate them
 * without parsing JSON. Having them in normalizedPayload ensures the settlement
 * engine (T3) can read them from the single JSON blob it already parses to
 * build CreditTransaction / DebitTransaction objects.
 *
 * fromBank — the bank that owns the DEBIT account (sending side). e.g. "HDFC
 * Bank", "ICICI Bank", "SBI Bank" toBank — the bank that owns the CREDIT
 * account (receiving side). e.g. "SBI Bank", "Axis Bank", "Kotak Bank"
 *
 * Each adapter hardcodes these values from the raw payload fields (e.g. from
 * IFSC codes, VPA handles, or explicit bank name fields).
 */
public class IncomingTransaction extends BaseEntity {

	private static final long serialVersionUID = 1L;

	// Unique identifier for this incoming transaction record
	private Long incomingTxnId;

	// HAS-A: which source system sent this transaction
	private SourceSystem sourceSystem;

	// The original reference number from the source system
	private String sourceRef;

	// The raw original payload as received — stored for audit/replay purposes
	private String rawPayload;

	// What kind of transaction is this? (CREDIT, DEBIT, REVERSAL etc.)
	private TransactionType txnType;

	// Transaction amount — NEVER use double or float for financial amounts
	private BigDecimal amount;

	// The date on which this transaction should be settled/valued
	private LocalDate valueDate;

	// Current processing stage of this transaction in the pipeline
	private ProcessingStatus processingStatus;

	// Exact timestamp when this transaction was received by our system
	private LocalDateTime ingestTimestamp;

	// The cleaned/standardised payload after adapter processing.
	// Always JSON format regardless of original source format.
	//
	// Keys in this JSON (all adapters must populate these):
	// "source" — source system name (CBS, RTGS, NEFT, UPI, FINTECH)
	// "sourceRef" — original reference number
	// "txnType" — transaction type
	// "amount" — transaction amount
	// "currency" — always INR for this system
	// "valueDate" — settlement date
	// "debitAccount" — sending/debiting account number (or VPA for UPI)
	// "creditAccount" — receiving/crediting account number (or VPA for UPI)
	// "fromBank" — name of bank owning the debit account ← NEW (v3)
	// "toBank" — name of bank owning the credit account ← NEW (v3)
	// + all source-specific extra fields (NARRATION, RBIRefNo, IFSC codes etc.)
	private String normalizedPayload;

	// -----------------------------------------------------------------------
	// NEW FIELDS (v3) — fromBank and toBank
	// -----------------------------------------------------------------------

	// Name of the bank that owns the DEBIT (sending) account.
	// Examples: "HDFC Bank", "ICICI Bank", "SBI Bank", "Axis Bank", "Kotak Bank"
	// Source: hardcoded from raw payload fields in each adapter.
	private String fromBank;

	// Name of the bank that owns the CREDIT (receiving) account.
	// Examples: "SBI Bank", "Axis Bank", "HDFC Bank", "Kotak Bank"
	// Source: hardcoded from raw payload fields in each adapter.
	private String toBank;

	// -----------------------------------------------------------------------
	// Constructors
	// -----------------------------------------------------------------------

	/**
	 * Default constructor.
	 */
	public IncomingTransaction() {
		super();
		this.processingStatus = ProcessingStatus.RECEIVED;
		this.ingestTimestamp = LocalDateTime.now();
	}

	/**
	 * Parameterized constructor — used by adapter classes.
	 *
	 * NOTE: fromBank and toBank are set separately using setFromBank() /
	 * setToBank() after calling this constructor, because each adapter extracts
	 * them differently.
	 *
	 * @param sourceSystem      The source system that sent this transaction
	 * @param sourceRef         Original reference from the source system
	 * @param rawPayload        The raw payload as received
	 * @param txnType           Type of transaction (CREDIT/DEBIT etc.)
	 * @param amount            Transaction amount (BigDecimal — no float/double!)
	 * @param valueDate         Settlement value date
	 * @param normalizedPayload Cleaned JSON payload (must contain fromBank and
	 *                          toBank keys)
	 */
	public IncomingTransaction(SourceSystem sourceSystem, String sourceRef, String rawPayload, TransactionType txnType,
			BigDecimal amount, LocalDate valueDate, String normalizedPayload) {
		super();
		this.sourceSystem = sourceSystem;
		this.sourceRef = sourceRef;
		this.rawPayload = rawPayload;
		this.txnType = txnType;
		this.amount = amount;
		this.valueDate = valueDate;
		this.normalizedPayload = normalizedPayload;
		this.processingStatus = ProcessingStatus.RECEIVED;
		this.ingestTimestamp = LocalDateTime.now();
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

	// -----------------------------------------------------------------------
	// NEW getters/setters (v3) — fromBank and toBank
	// -----------------------------------------------------------------------

	public String getFromBank() {
		return fromBank;
	}

	public void setFromBank(String fromBank) {
		this.fromBank = fromBank;
	}

	public String getToBank() {
		return toBank;
	}

	public void setToBank(String toBank) {
		this.toBank = toBank;
	}

	// -----------------------------------------------------------------------
	// toString
	// -----------------------------------------------------------------------

	@Override
	public String toString() {
		return "IncomingTransaction{" + "incomingTxnId=" + incomingTxnId + ", sourceSystem="
				+ (sourceSystem != null ? sourceSystem.getSystemCode() : "null") + ", sourceRef='" + sourceRef + '\''
				+ ", txnType=" + txnType + ", amount=" + amount + ", valueDate=" + valueDate + ", fromBank='" + fromBank
				+ '\'' + ", toBank='" + toBank + '\'' + ", processingStatus=" + processingStatus + ", ingestTimestamp="
				+ ingestTimestamp + '}';
	}
}