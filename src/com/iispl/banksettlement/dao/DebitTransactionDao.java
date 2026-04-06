package com.iispl.banksettlement.dao;

import com.iispl.banksettlement.entity.DebitTransaction;

import java.util.List;

/**
 * DebitTransactionDao — Interface for all DB operations on debit_transaction table.
 *
 * Used by the settlement engine after it reads a QUEUED IncomingTransaction
 * with txnType=DEBIT and builds a DebitTransaction object from it.
 */
public interface DebitTransactionDao {

    /**
     * Saves a new DebitTransaction to the debit_transaction table.
     * After saving, the generated DB txn_id is set back on the object.
     *
     * @param txn The DebitTransaction to save
     */
    void save(DebitTransaction txn);

    /**
     * Finds a DebitTransaction by its primary key (txn_id).
     *
     * @param txnId The primary key in debit_transaction table
     * @return DebitTransaction if found, null if not found
     */
    DebitTransaction findById(Long txnId);

    /**
     * Finds all DebitTransactions linked to a given incoming transaction.
     *
     * @param incomingTxnId The incoming_txn_id FK
     * @return List of matching DebitTransaction objects (usually just one)
     */
    List<DebitTransaction> findByIncomingTxnId(Long incomingTxnId);

    /**
     * Updates the status of a DebitTransaction.
     *
     * @param txnId  The primary key in debit_transaction table
     * @param status The new status string (e.g. "SETTLED")
     */
    void updateStatus(Long txnId, String status);
}
