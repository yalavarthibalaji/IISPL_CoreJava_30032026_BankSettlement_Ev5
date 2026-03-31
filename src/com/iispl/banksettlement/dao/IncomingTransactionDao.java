package com.iispl.banksettlement.dao;

import com.iispl.banksettlement.entity.IncomingTransaction;

import com.iispl.banksettlement.enums.ProcessingStatus;

import java.util.List;

/**
 *
 * 
 * IncomingTransactionDao
 *
 * 
 * 
 * Interface defining all database operations for IncomingTransaction.
 * 
 * T3 teammate writes the implementation of this interface.
 *
 *
 * 
 * 
 * RULE: This interface only declares WHAT to do.
 * 
 * 
 * IncomingTransactionDaoImpl decides HOW to do it using JDBC.
 * 
 */

public interface IncomingTransactionDao {

	/**
	 * 
	 * Saves a new IncomingTransaction record to the DB.
	 * 
	 * After saving, the generated DB id is set back on the object.
	 *
	 * 
	 * 
	 * 
	 * @param txn The IncomingTransaction object to save
	 * 
	 * 
	 */

	void save(IncomingTransaction txn);

	/**
	 * 
	 * Finds a transaction by its auto-generated DB primary key.
	 *
	 * 
	 * 
	 * 
	 * @param incomingTxnId The primary key
	 * 
	 * @return IncomingTransaction if found, null if not found
	 * 
	 */

	IncomingTransaction findById(Long incomingTxnId);

	/**
	 * 
	 * Finds a transaction by its source system reference number.
	 * 
	 * Used for duplicate detection — same sourceRef = duplicate transaction.
	 *
	 * 
	 * 
	 * 
	 * @param sourceRef The reference number from the source system
	 *
	 * 
	 * @return IncomingTransaction if found, null if not found
	 * 
	 * 
	 */

	IncomingTransaction findBySourceRef(String sourceRef);

	/**
	 * 
	 * 
	 * Checks if a transaction with the given sourceRef already exists.
	 * 
	 * Faster than findBySourceRef when you only need a yes/no answer.
	 *
	 * 
	 * 
	 * 
	 * @param sourceRef The reference number to check
	 * 
	 * @return true if already exists, false if new
	 * 
	 */

	boolean existsBySourceRef(String sourceRef);

	/**
	 * 
	 * Returns all transactions currently in a given processing status.
	 * 
	 * Used by the settlement engine to find QUEUED transactions.
	 *
	 * 
	 * 
	 * 
	 * @param status The ProcessingStatus to filter by
	 * 
	 * @return List of matching IncomingTransaction objects
	 * 
	 */

	List<IncomingTransaction> findByStatus(ProcessingStatus status);

	/**
	 *
	 * 
	 * Updates the processing status of a transaction.
	 *
	 * 
	 * Called at each stage: RECEIVED → VALIDATED → QUEUED → PROCESSED
	 *
	 *
	 * 
	 * 
	 * @param incomingTxnId The transaction to update
	 *
	 * 
	 * @param status        The new status to set
	 * 
	 * 
	 */

	void updateStatus(Long incomingTxnId, ProcessingStatus status);

}
