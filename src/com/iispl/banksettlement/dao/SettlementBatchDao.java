package com.iispl.banksettlement.dao;

import com.iispl.banksettlement.entity.SettlementBatch;
import com.iispl.banksettlement.entity.SettlementRecord;

import java.util.List;

/**
 * SettlementBatchDao — Interface for all DB operations on
 * settlement_batch and settlement_record tables.
 *
 * WHY TWO TABLES IN ONE DAO?
 *   SettlementBatch HAS-A List<SettlementRecord> — COMPOSITION.
 *   A SettlementRecord cannot exist without a batch.
 *   So both tables are managed together from one DAO.
 *
 * TABLES:
 *   settlement_batch  — one row per channel per run (e.g. BATCH-CBS-20260406)
 *   settlement_record — one row per transaction settled inside a batch
 *
 * PACKAGE: com.iispl.banksettlement.dao
 */
public interface SettlementBatchDao {

    // -----------------------------------------------------------------------
    // settlement_batch operations
    // -----------------------------------------------------------------------

    /**
     * Inserts a new row into settlement_batch.
     * Call this BEFORE processing any transactions — status will be RUNNING.
     *
     * @param batch The SettlementBatch to insert
     */
    void saveBatch(SettlementBatch batch);

    /**
     * Updates batch_status, total_transactions, total_amount of an existing batch.
     * Call this AFTER all transactions in the batch are processed.
     * Final status will be: COMPLETED, PARTIAL, or FAILED.
     *
     * @param batch The SettlementBatch with updated values to write back to DB
     */
    void updateBatch(SettlementBatch batch);

    /**
     * Finds a SettlementBatch by its primary key (batch_id).
     *
     * @param batchId  e.g. "BATCH-CBS-20260406"
     * @return SettlementBatch if found, null if not found
     */
    SettlementBatch findBatchById(String batchId);

    // -----------------------------------------------------------------------
    // settlement_record operations
    // -----------------------------------------------------------------------

    /**
     * Inserts one SettlementRecord into settlement_record table.
     * Call this once per transaction after it is settled or failed.
     * DB-generated record_id is set back on the object after save.
     *
     * @param record The SettlementRecord to insert
     */
    void saveRecord(SettlementRecord record);

    /**
     * Returns all SettlementRecords that belong to a given batchId.
     *
     * @param batchId  FK linking records to their batch
     * @return List of SettlementRecord (empty list if none found)
     */
    List<SettlementRecord> findRecordsByBatchId(String batchId);
}