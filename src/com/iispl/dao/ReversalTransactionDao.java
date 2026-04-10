package com.iispl.dao;

import java.util.List;

import com.iispl.entity.ReversalTransaction;

/**
 * ReversalTransactionDao — Interface for all DB operations on
 * reversal_transaction table.
 *
 * Used by the settlement engine after it reads a QUEUED IncomingTransaction
 * with txnType=REVERSAL and builds a ReversalTransaction object from it.
 */
public interface ReversalTransactionDao {

	/**
	 * Saves a new ReversalTransaction to the reversal_transaction table. After
	 * saving, the generated DB txn_id is set back on the object.
	 *
	 * @param txn The ReversalTransaction to save
	 */
	void save(ReversalTransaction txn);

	/**
	 * Finds a ReversalTransaction by its primary key (txn_id).
	 *
	 * @param txnId The primary key in reversal_transaction table
	 * @return ReversalTransaction if found, null if not found
	 */
	ReversalTransaction findById(Long txnId);

	/**
	 * Finds all ReversalTransactions linked to a given incoming transaction.
	 *
	 * @param incomingTxnId The incoming_txn_id FK
	 * @return List of matching ReversalTransaction objects
	 */
	List<ReversalTransaction> findByIncomingTxnId(Long incomingTxnId);

	/**
	 * Finds all ReversalTransactions that reference a given original transaction.
	 * Used to check if a transaction has already been reversed.
	 *
	 * @param originalTxnRef The sourceRef of the original transaction
	 * @return List of ReversalTransaction objects pointing to that original
	 */
	List<ReversalTransaction> findByOriginalTxnRef(String originalTxnRef);

	/**
	 * Updates the status of a ReversalTransaction.
	 *
	 * @param txnId  The primary key in reversal_transaction table
	 * @param status The new status string (e.g. "SETTLED")
	 */
	void updateStatus(Long txnId, String status);
}
