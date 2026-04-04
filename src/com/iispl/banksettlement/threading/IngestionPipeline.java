package com.iispl.banksettlement.threading;

import com.iispl.banksettlement.dao.IncomingTransactionDao;
import com.iispl.banksettlement.dao.impl.IncomingTransactionDaoImpl;
import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.registry.AdapterRegistry;
import com.iispl.connectionpool.ConnectionPool;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * IngestionPipeline — creates and submits IngestionWorker tasks.
 *
 * Holds the shared infrastructure: AdapterRegistry, BlockingQueue,
 * ThreadPoolExecutor, and IncomingTransactionDao.
 *
 * CHANGE LOG (v2):
 *   - AccountDao and CustomerDao fields REMOVED.
 *     Account/customer validation no longer happens in the ingestion phase.
 *     It has moved to the settlement engine (T3's responsibility).
 *   - IngestionWorker constructor no longer receives AccountDao / CustomerDao.
 *   - The public ingest() method signature is unchanged — callers are unaffected.
 */
public class IngestionPipeline {

    private final BlockingQueue<IncomingTransaction> blockingQueue =
            new LinkedBlockingQueue<>(500);

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            5, 20, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final AdapterRegistry adapterRegistry;
    private final IncomingTransactionDao incomingTransactionDao;

    public IngestionPipeline() {
        this.adapterRegistry        = new AdapterRegistry();
        this.incomingTransactionDao = new IncomingTransactionDaoImpl();

        System.out.println("[IngestionPipeline] Initialised — pool ready, queue ready, " +
                           "adapters registered, DAO created.");
    }

    /**
     * Submits one raw payload for the given source type to the thread pool.
     * The IngestionWorker will adapt, deduplicate, save, and queue the transaction.
     *
     * @param sourceType the source system this payload came from
     * @param rawPayload the raw string payload (pipe-delimited, XML, fixed-width, or JSON)
     */
    public void ingest(SourceType sourceType, String rawPayload) {

        IngestionWorker worker = new IngestionWorker(
                sourceType,
                rawPayload,
                adapterRegistry,
                blockingQueue,
                incomingTransactionDao
        );

        executor.submit(worker);
        System.out.println("[IngestionPipeline] Submitted task for: " + sourceType);
    }

    public BlockingQueue<IncomingTransaction> getBlockingQueue() {
        return blockingQueue;
    }

    public int getQueueSize() {
        return blockingQueue.size();
    }

    /**
     * Gracefully shuts down the thread pool and connection pool.
     * Must be called after all ingest() calls are done.
     */
    public void shutdown() {

        System.out.println("[IngestionPipeline] Shutting down...");

        executor.shutdown();

        try {
            boolean finished = executor.awaitTermination(60, TimeUnit.SECONDS);

            if (!finished) {
                System.out.println("[IngestionPipeline] Timeout — forcing remaining threads to stop.");
                executor.shutdownNow();
                executor.awaitTermination(10, TimeUnit.SECONDS);
            }

            System.out.println("[IngestionPipeline] Clean shutdown done.");

        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();

        } finally {
            // Only close the pool AFTER all worker threads are fully stopped
            ConnectionPool.shutdown();
            System.out.println("[IngestionPipeline] ConnectionPool closed.");
        }
    }
}