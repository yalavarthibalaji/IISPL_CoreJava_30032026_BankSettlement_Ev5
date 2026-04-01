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
 * IngestionPipeline — Wires ingestion phase components.
 */

public class IngestionPipeline {

    // Queue (capacity = 500)
    private final BlockingQueue<IncomingTransaction> blockingQueue =
            new LinkedBlockingQueue<>(500);

    // Thread Pool
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            5,
            20,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final AdapterRegistry adapterRegistry;
    private final IncomingTransactionDao incomingTransactionDao;

    // Constructor
    public IngestionPipeline() {
        this.adapterRegistry = new AdapterRegistry();
        this.incomingTransactionDao = new IncomingTransactionDaoImpl();

        System.out.println("[IngestionPipeline] Initialised — pool ready, queue ready, adapters registered.");
    }

    /**
<<<<<<< HEAD
     * Submit payload for processing
=======
     * Submit payload for s
>>>>>>> t1/balaji
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

    /**
     * Get queue for downstream processing
     */
    public BlockingQueue<IncomingTransaction> getBlockingQueue() {
        return blockingQueue;
    }

    public int getQueueSize() {
        return blockingQueue.size();
    }

    /**
     * Shutdown pipeline
     */
    public void shutdown() {

        System.out.println("[IngestionPipeline] Shutting down...");

        executor.shutdown();

        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                System.out.println("[IngestionPipeline] Forced shutdown.");
            } else {
                System.out.println("[IngestionPipeline] Clean shutdown done.");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            ConnectionPool.shutdown();
            System.out.println("[IngestionPipeline] ConnectionPool closed.");
        }
    }
}