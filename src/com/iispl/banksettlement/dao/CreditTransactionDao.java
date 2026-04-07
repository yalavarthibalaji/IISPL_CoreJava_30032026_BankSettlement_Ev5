package com.iispl.banksettlement.dao;

import com.iispl.banksettlement.entity.CreditTransaction;

import java.util.List;

/**
 * CreditTransactionDao — Interface for all DB operations on credit_transaction
 * table.
 *
 * Used by the settlement engine after it reads a QUEUED IncomingTransaction
 * with txnType=CREDIT and builds a CreditTransaction object from it.
 */
public interface CreditTransactionDao {

	/**
	 * Saves a new CreditTransaction to the credit_transaction table. After saving,
	 * the generated DB txn_id is set back on the object.
	 *
	 * @param txn The CreditTransaction to save
	 */
	void save(CreditTransaction txn);

	/**
	 * Finds a CreditTransaction by its primary key (txn_id).
	 *
	 * @param txnId The primary key in credit_transaction table
	 * @return CreditTransaction if found, null if not found
	 */
	CreditTransaction findById(Long txnId);

	/**
	 * Finds all CreditTransactions linked to a given incoming transaction.
	 *
	 * @param incomingTxnId The incoming_txn_id FK
	 * @return List of matching CreditTransaction objects (usually just one)
	 */
	List<CreditTransaction> findByIncomingTxnId(Long incomingTxnId);

	/**
	 * Updates the status of a CreditTransaction. Called as: INITIATED → VALIDATED →
	 * PENDING_SETTLEMENT → SETTLED or FAILED
	 *
	 * @param txnId  The primary key in credit_transaction table
	 * @param status The new status string (e.g. "SETTLED")
	 */
	void updateStatus(Long txnId, String status);
}
