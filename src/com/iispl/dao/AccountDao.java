package com.iispl.dao;

import com.iispl.entity.Account;

/**
 * AccountDao — Interface for Account database operations.
 *
 * Used during ingestion validation to check if an account exists and is ACTIVE
 * before accepting a transaction.
 *
 * RULE: Interface declares WHAT to do. AccountDaoImpl decides HOW using JDBC.
 */
public interface AccountDao {

	/**
	 * Finds an Account by its account number. Returns null if not found.
	 *
	 * @param accountNumber The account number to search for
	 * @return Account if found, null if not found
	 */
	Account findByAccountNumber(String accountNumber);

	/**
	 * Checks if an account with this number exists AND is ACTIVE. This is the main
	 * validation method used during ingestion.
	 *
	 * @param accountNumber The account number to check
	 * @return true if account exists and status = ACTIVE, false otherwise
	 */
	boolean isAccountActiveByNumber(String accountNumber);
}