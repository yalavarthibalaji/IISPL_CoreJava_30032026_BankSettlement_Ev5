package com.iispl.banksettlement.dao;

import com.iispl.banksettlement.entity.InterBankTransaction;

import java.util.List;

/**
 * InterBankTransactionDao — Interface for all DB operations on
 * interbank_transaction table.
 *
 * Used by the settlement engine after it reads a QUEUED IncomingTransaction
 * with txnType=INTRABANK and builds an InterBankTransaction object from it.
 */
public interface InterBankTransactionDao {

	/**
	 * Saves a new InterBankTransaction to the interbank_transaction table. After
	 * saving, the generated DB txn_id is set back on the object.
	 *
	 * @param txn The InterBankTransaction to save
	 */
	void save(InterBankTransaction txn);

	/**
	 * Finds an InterBankTransaction by its primary key (txn_id).
	 *
	 * @param txnId The primary key in interbank_transaction table
	 * @return InterBankTransaction if found, null if not found
	 */
	InterBankTransaction findById(Long txnId);

	/**
	 * Finds all InterBankTransactions linked to a given incoming transaction.
	 *
	 * @param incomingTxnId The incoming_txn_id FK
	 * @return List of matching InterBankTransaction objects
	 */
	List<InterBankTransaction> findByIncomingTxnId(Long incomingTxnId);

	/**
	 * Finds all InterBankTransactions involving a specific correspondent bank.
	 * Useful for netting: how much do we owe/receive from a specific counterparty?
	 *
	 * @param correspondentBankCode IFSC or BIC code of the counterparty bank
	 * @return List of InterBankTransaction objects for that bank
	 */
	List<InterBankTransaction> findByCorrespondentBankCode(String correspondentBankCode);

	/**
	 * Updates the status of an InterBankTransaction.
	 *
	 * @param txnId  The primary key in interbank_transaction table
	 * @param status The new status string (e.g. "SETTLED")
	 */
	void updateStatus(Long txnId, String status);
}
