package com.iispl.banksettlement.threading;

import com.iispl.banksettlement.adapter.TransactionAdapter;
import com.iispl.banksettlement.dao.IncomingTransactionDao;
import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.registry.AdapterRegistry;

import java.util.concurrent.BlockingQueue;

/**
 * IngestionWorker — One Runnable per source system.
 *
 * WHAT IT DOES:
 *   1. Receives a raw payload string from a source system
 *   2. Uses AdapterRegistry to get the right adapter
 *   3. Calls adapt() to get a canonical IncomingTransaction
 *   4. Saves the IncomingTransaction to DB via DAO
 *   5. Puts it on the BlockingQueue for the SettlementProcessor
 *
 * WHY Runnable and not Callable:
 *   - No return value needed
 *   - Fire-and-forget ingestion
 */
public class IngestionWorker implements Runnable {

    private final SourceType sourceType;
    private final String rawPayload;
    private final AdapterRegistry adapterRegistry;
    private final BlockingQueue<IncomingTransaction> blockingQueue;
    private final IncomingTransactionDao incomingTransactionDao;

    // Constructor
    public IngestionWorker(SourceType sourceType,
                           String rawPayload,
                           AdapterRegistry adapterRegistry,
                           BlockingQueue<IncomingTransaction> blockingQueue,
                           IncomingTransactionDao incomingTransactionDao) {

        this.sourceType = sourceType;
        this.rawPayload = rawPayload;
        this.adapterRegistry = adapterRegistry;
        this.blockingQueue = blockingQueue;
        this.incomingTransactionDao = incomingTransactionDao;
    }

    // run() — executed by thread
    @Override
    public void run() {

        System.out.println("[IngestionWorker] Thread started for source: "
                + sourceType + " | Thread: " + Thread.currentThread().getName());

        try {
            // STEP 1: Get adapter
            TransactionAdapter adapter = adapterRegistry.getAdapter(sourceType);

            // STEP 2: Convert payload
            IncomingTransaction txn = adapter.adapt(rawPayload);

            System.out.println("[IngestionWorker] Adapted txn: "
                    + txn.getSourceRef()
                    + " | Amount: " + txn.getAmount()
                    + " " + txn.getCurrency());

            // STEP 3: Duplicate check
            if (incomingTransactionDao.existsBySourceRef(txn.getSourceRef())) {
                System.out.println("[IngestionWorker] DUPLICATE detected — skipping sourceRef: "
                        + txn.getSourceRef());
                return;
            }

            // STEP 4: Save to DB
            incomingTransactionDao.save(txn);
            System.out.println("[IngestionWorker] Saved to DB — id: "
                    + txn.getIncomingTxnId());

            // STEP 5: Update status
            txn.setProcessingStatus(ProcessingStatus.QUEUED);
            incomingTransactionDao.updateStatus(
                    txn.getIncomingTxnId(),
                    ProcessingStatus.QUEUED
            );

            // STEP 6: Put into queue
            blockingQueue.put(txn);

            System.out.println("[IngestionWorker] Queued txn: "
                    + txn.getSourceRef()
                    + " | Queue size now: " + blockingQueue.size());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[IngestionWorker] Thread interrupted for " + sourceType);

        } catch (Exception e) {
            System.out.println("[IngestionWorker] ERROR processing payload from "
                    + sourceType + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}