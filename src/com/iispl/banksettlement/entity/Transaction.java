package com.iispl.banksettlement.entity;

import com.iispl.banksettlement.enums.TransactionStatus;
import com.iispl.banksettlement.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Transaction — Abstract parent class for ALL transaction subtypes.
 *
 * WHY ABSTRACT? You will never create a plain "Transaction" object in real
 * life. Every real transaction is always one of: - CreditTransaction (money
 * coming IN) - DebitTransaction (money going OUT) - InterBankTransaction
 * (transfer between two banks) - ReversalTransaction (undo of a previous
 * transaction)
 *
 * So we make Transaction abstract to force usage of the concrete subclasses.
 *
 * IS-A hierarchy: Transaction extends BaseEntity CreditTransaction extends
 * Transaction DebitTransaction extends Transaction InterBankTransaction extends
 * Transaction ReversalTransaction extends Transaction
 *
 * Common fields shared by ALL transaction types are defined here.
 * Subclass-specific fields are defined in each subclass.
 */
public abstract class Transaction extends BaseEntity {

	private static final long serialVersionUID = 1L;

	// Unique identifier for this transaction
	private Long txnId;

	// The account from which money is debited (source account)
	private Long debitAccountId;

	// The account to which money is credited (destination account)
	private Long creditAccountId;

	// Transaction amount — ALWAYS use BigDecimal for money
	// Never use double/float — they lose precision for financial calculations
	private BigDecimal amount;

	// ISO 4217 currency code e.g. "INR", "USD", "EUR"
	private String currency;

	// Exact date and time this transaction was created in the system
	private LocalDateTime txnDate;

	// The date on which this transaction should actually be settled
	// (can be different from txnDate — e.g. future-dated payments)
	private LocalDate valueDate;

	// Current lifecycle status of this transaction
	private TransactionStatus status;

	// Business reference number — used for matching with external systems
	private String referenceNumber;

	// What kind of transaction is this?
	private TransactionType txnType;

	// -----------------------------------------------------------------------
	// Constructors
	// -----------------------------------------------------------------------

	/**
	 * Default constructor. Sets txnDate to now and status to INITIATED.
	 */
	public Transaction() {
		super(); // calls BaseEntity() — sets createdAt, updatedAt
		this.txnDate = LocalDateTime.now();
		this.status = TransactionStatus.INITIATED;
	}

	/**
	 * Parameterized constructor — use this when you have all fields ready.
	 *
	 * @param debitAccountId  ID of the account being debited
	 * @param creditAccountId ID of the account being credited
	 * @param amount          Transaction amount (BigDecimal — no float/double!)
	 * @param currency        ISO currency code e.g. "INR"
	 * @param valueDate       Date on which settlement should happen
	 * @param referenceNumber External reference number for tracking
	 * @param txnType         Type of transaction (CREDIT, DEBIT, REVERSAL etc.)
	 */
	public Transaction(Long debitAccountId, Long creditAccountId, BigDecimal amount, String currency,
			LocalDate valueDate, String referenceNumber, TransactionType txnType) {
		super();
		this.debitAccountId = debitAccountId;
		this.creditAccountId = creditAccountId;
		this.amount = amount;
		this.currency = currency;
		this.valueDate = valueDate;
		this.referenceNumber = referenceNumber;
		this.txnType = txnType;
		this.txnDate = LocalDateTime.now();
		this.status = TransactionStatus.INITIATED;
	}

	// -----------------------------------------------------------------------
	// Getters and Setters
	// -----------------------------------------------------------------------

	public Long getTxnId() {
		return txnId;
	}

	public void setTxnId(Long txnId) {
		this.txnId = txnId;
	}

	public Long getDebitAccountId() {
		return debitAccountId;
	}

	public void setDebitAccountId(Long debitAccountId) {
		this.debitAccountId = debitAccountId;
	}

	public Long getCreditAccountId() {
		return creditAccountId;
	}

	public void setCreditAccountId(Long creditAccountId) {
		this.creditAccountId = creditAccountId;
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

	public LocalDateTime getTxnDate() {
		return txnDate;
	}

	public void setTxnDate(LocalDateTime txnDate) {
		this.txnDate = txnDate;
	}

	public LocalDate getValueDate() {
		return valueDate;
	}

	public void setValueDate(LocalDate valueDate) {
		this.valueDate = valueDate;
	}

	public TransactionStatus getStatus() {
		return status;
	}

	public void setStatus(TransactionStatus status) {
		this.status = status;
	}

	public String getReferenceNumber() {
		return referenceNumber;
	}

	public void setReferenceNumber(String referenceNumber) {
		this.referenceNumber = referenceNumber;
	}

	public TransactionType getTxnType() {
		return txnType;
	}

	public void setTxnType(TransactionType txnType) {
		this.txnType = txnType;
	}

	// -----------------------------------------------------------------------
	// toString — useful for logging and debugging
	// -----------------------------------------------------------------------

	@Override
	public String toString() {
		return "Transaction{" + "txnId=" + txnId + ", debitAccountId=" + debitAccountId + ", creditAccountId="
				+ creditAccountId + ", amount=" + amount + ", currency='" + currency + '\'' + ", txnDate=" + txnDate
				+ ", valueDate=" + valueDate + ", status=" + status + ", referenceNumber='" + referenceNumber + '\''
				+ ", txnType=" + txnType + '}';
	}
}
