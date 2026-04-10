package com.iispl.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.iispl.enums.TransactionType;

/**
 * CreditTransaction — Represents a transaction where money comes INTO an
 * account.
 *
 * Examples: - A customer receives a salary payment - An incoming NEFT transfer
 * from another bank - An incoming SWIFT wire from a foreign bank
 *
 * IS-A Transaction (extends Transaction) IS-A BaseEntity (inherits through
 * Transaction)
 *
 * Extra field in this subclass: - creditAccountNumber : The actual bank account
 * number receiving the money (in addition to the creditAccountId FK from
 * Transaction)
 *
 * FLOW: IncomingTransaction (CREDIT type) → gets converted to CreditTransaction
 * → SettlementEngine processes → balance is added to creditAccountNumber
 */
public class CreditTransaction extends Transaction {

	private static final long serialVersionUID = 1L;

	// The actual account number (string) of the account receiving the money.
	// This is the human-readable account number like "SB1234567890"
	// (creditAccountId in parent is the internal DB foreign key — Long)
	private String creditAccountNumber;

	// -----------------------------------------------------------------------
	// Constructors
	// -----------------------------------------------------------------------

	/**
	 * Default constructor. txnType is automatically set to CREDIT.
	 */
	public CreditTransaction() {
		super();
		// A CreditTransaction is always of type CREDIT — set it here so
		// nobody can forget to set it
		setTxnType(TransactionType.CREDIT);
	}

	/**
	 * Parameterized constructor — use this to create a fully populated
	 * CreditTransaction in one shot.
	 *
	 * @param debitAccountId      ID of the account sending the money
	 * @param creditAccountId     ID of the account receiving the money (DB FK)
	 * @param creditAccountNumber Actual account number receiving the money
	 * @param amount              Amount being credited (BigDecimal — no float!)
	 * @param currency            ISO currency code e.g. "INR"
	 * @param valueDate           Date on which the credit should be applied
	 * @param referenceNumber     External reference for tracking
	 */
	public CreditTransaction(Long debitAccountId, Long creditAccountId, String creditAccountNumber, BigDecimal amount,
			String currency, LocalDate valueDate, String referenceNumber) {
		super(debitAccountId, creditAccountId, amount, currency, valueDate, referenceNumber, TransactionType.CREDIT);
		this.creditAccountNumber = creditAccountNumber;
	}

	// -----------------------------------------------------------------------
	// Getters and Setters
	// -----------------------------------------------------------------------

	public String getCreditAccountNumber() {
		return creditAccountNumber;
	}

	public void setCreditAccountNumber(String creditAccountNumber) {
		this.creditAccountNumber = creditAccountNumber;
	}

	// -----------------------------------------------------------------------
	// toString
	// -----------------------------------------------------------------------

	@Override
	public String toString() {
		return "CreditTransaction{" + "txnId=" + getTxnId() + ", creditAccountNumber='" + creditAccountNumber + '\''
				+ ", creditAccountId=" + getCreditAccountId() + ", amount=" + getAmount() + ", currency='"
				+ getCurrency() + '\'' + ", valueDate=" + getValueDate() + ", status=" + getStatus()
				+ ", referenceNumber='" + getReferenceNumber() + '\'' + '}';
	}
}
