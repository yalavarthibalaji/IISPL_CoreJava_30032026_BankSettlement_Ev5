package com.iispl.banksettlement.entity;

import com.iispl.banksettlement.enums.AccountStatus;
import com.iispl.banksettlement.enums.AccountType;

import java.math.BigDecimal;

/**
 * Account — Represents a bank account in the settlement system.
 *
 * During ingestion validation, the IngestionWorker checks whether the
 * debitAccountNumber and creditAccountNumber from the incoming transaction
 * exist and are ACTIVE in this table.
 *
 * If either account is not found or is not ACTIVE, the transaction is rejected
 * with status FAILED.
 *
 * Extends BaseEntity: inherits id, createdAt, updatedAt, createdBy, version.
 */
public class Account extends BaseEntity {

	private static final long serialVersionUID = 1L;

	// Unique bank account number e.g. "ACC001", "NOSTRO-001"
	private String accountNumber;

	// Type of account: NOSTRO, VOSTRO, CURRENT, SAVINGS, SUSPENSE, CORRESPONDENT
	private AccountType accountType;

	// Current balance — BigDecimal for financial precision
	private BigDecimal balance;

	// ISO 4217 currency code e.g. "INR", "USD"
	private String currency;

	// Foreign key to Customer table — who owns this account
	private Long customerId;

	// Foreign key to Bank — which bank this account belongs to
	private Long bankId;

	// Whether this account is ACTIVE, FROZEN, CLOSED etc.
	private AccountStatus status;

	// -----------------------------------------------------------------------
	// Constructors
	// -----------------------------------------------------------------------

	public Account() {
		super();
	}

	public Account(String accountNumber, AccountType accountType, BigDecimal balance, String currency, Long customerId,
			Long bankId, AccountStatus status) {
		super();
		this.accountNumber = accountNumber;
		this.accountType = accountType;
		this.balance = balance;
		this.currency = currency;
		this.customerId = customerId;
		this.bankId = bankId;
		this.status = status;
	}

	// -----------------------------------------------------------------------
	// Getters and Setters
	// -----------------------------------------------------------------------

	public String getAccountNumber() {
		return accountNumber;
	}

	public void setAccountNumber(String accountNumber) {
		this.accountNumber = accountNumber;
	}

	public AccountType getAccountType() {
		return accountType;
	}

	public void setAccountType(AccountType accountType) {
		this.accountType = accountType;
	}

	public BigDecimal getBalance() {
		return balance;
	}

	public void setBalance(BigDecimal balance) {
		this.balance = balance;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public Long getCustomerId() {
		return customerId;
	}

	public void setCustomerId(Long customerId) {
		this.customerId = customerId;
	}

	public Long getBankId() {
		return bankId;
	}

	public void setBankId(Long bankId) {
		this.bankId = bankId;
	}

	public AccountStatus getStatus() {
		return status;
	}

	public void setStatus(AccountStatus status) {
		this.status = status;
	}

	// -----------------------------------------------------------------------
	// toString
	// -----------------------------------------------------------------------

	@Override
	public String toString() {
		return "Account{" + "accountNumber='" + accountNumber + '\'' + ", accountType=" + accountType + ", balance="
				+ balance + ", currency='" + currency + '\'' + ", status=" + status + '}';
	}
}