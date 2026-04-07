package com.iispl.banksettlement.entity;

import com.iispl.banksettlement.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DebitTransaction — Represents a transaction where money goes OUT of an
 * account.
 *
 * Examples: - A customer initiates an outgoing NEFT payment - An outgoing RTGS
 * transfer to another bank - A bill payment being processed
 *
 * IS-A Transaction (extends Transaction) IS-A BaseEntity (inherits through
 * Transaction)
 *
 * Extra field in this subclass: - debitAccountNumber : The actual bank account
 * number from which money is taken (in addition to the debitAccountId FK from
 * Transaction)
 *
 * FLOW: IncomingTransaction (DEBIT type) → gets converted to DebitTransaction →
 * SettlementEngine processes → balance is deducted from debitAccountNumber
 */
public class DebitTransaction extends Transaction {

	private static final long serialVersionUID = 1L;

	// The actual account number (string) of the account being debited.
	// This is the human-readable account number like "CA9876543210"
	// (debitAccountId in parent is the internal DB foreign key — Long)
	private String debitAccountNumber;

	// -----------------------------------------------------------------------
	// Constructors
	// -----------------------------------------------------------------------

	/**
	 * Default constructor. txnType is automatically set to DEBIT.
	 */
	public DebitTransaction() {
		super();
		// A DebitTransaction is always of type DEBIT — set it here so
		// nobody can forget to set it
		setTxnType(TransactionType.DEBIT);
	}

	/**
	 * Parameterized constructor — use this to create a fully populated
	 * DebitTransaction in one shot.
	 *
	 * @param debitAccountId     ID of the account being debited (DB FK)
	 * @param debitAccountNumber Actual account number money is taken from
	 * @param creditAccountId    ID of the account receiving the money
	 * @param amount             Amount being debited (BigDecimal — no float!)
	 * @param currency           ISO currency code e.g. "INR"
	 * @param valueDate          Date on which the debit should be applied
	 * @param referenceNumber    External reference for tracking
	 */
	public DebitTransaction(Long debitAccountId, String debitAccountNumber, Long creditAccountId, BigDecimal amount,
			String currency, LocalDate valueDate, String referenceNumber) {
		super(debitAccountId, creditAccountId, amount, currency, valueDate, referenceNumber, TransactionType.DEBIT);
		this.debitAccountNumber = debitAccountNumber;
	}

	// -----------------------------------------------------------------------
	// Getters and Setters
	// -----------------------------------------------------------------------

	public String getDebitAccountNumber() {
		return debitAccountNumber;
	}

	public void setDebitAccountNumber(String debitAccountNumber) {
		this.debitAccountNumber = debitAccountNumber;
	}

	// -----------------------------------------------------------------------
	// toString
	// -----------------------------------------------------------------------

	@Override
	public String toString() {
		return "DebitTransaction{" + "txnId=" + getTxnId() + ", debitAccountNumber='" + debitAccountNumber + '\''
				+ ", debitAccountId=" + getDebitAccountId() + ", amount=" + getAmount() + ", currency='" + getCurrency()
				+ '\'' + ", valueDate=" + getValueDate() + ", status=" + getStatus() + ", referenceNumber='"
				+ getReferenceNumber() + '\'' + '}';
	}
}
