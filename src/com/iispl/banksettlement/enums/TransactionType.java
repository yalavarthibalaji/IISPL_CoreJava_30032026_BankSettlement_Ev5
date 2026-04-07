package com.iispl.banksettlement.enums;

/**
 * Classifies the nature/type of a financial transaction.
 */
public enum TransactionType {
	CREDIT, // Money coming into an account
	DEBIT, // Money going out of an account
	REVERSAL, // Reversal of a previous transaction
	SWAP, // Currency/asset swap
	FEE, // Service fee or charge
	INTRABANK // Transfer within the same bank
}
