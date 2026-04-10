package com.iispl.enums;

/**
 * Defines the category/type of a bank account.
 */
public enum AccountType {
	NOSTRO, // Our account held at a foreign bank (foreign currency)
	VOSTRO, // Foreign bank's account held at our bank
	CURRENT, // Current/checking account for businesses
	SAVINGS, // Savings account for retail customers
	SUSPENSE, // Temporary holding account for unmatched entries
	CORRESPONDENT // Correspondent banking account for interbank settlements
}
