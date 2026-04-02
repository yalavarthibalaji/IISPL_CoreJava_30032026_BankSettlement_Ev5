package com.iispl.banksettlement.threading;

import com.iispl.banksettlement.dao.AccountDao;
import com.iispl.banksettlement.dao.CustomerDao;
import com.iispl.banksettlement.dao.IncomingTransactionDao;
import com.iispl.banksettlement.dao.impl.AccountDaoImpl;
import com.iispl.banksettlement.dao.impl.CustomerDaoImpl;
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
 * IngestionPipeline — Wires all ingestion phase components together.
 *
 * Now includes AccountDao and CustomerDao for validation during ingestion.
 * The ingest() method accepts one raw payload string and submits it
 * as an IngestionWorker task to the thread pool.
 */
public class IngestionPipeline {

    // Queue capacity — maximum 500 transactions waiting for settlement
    private final BlockingQueue<IncomingTransaction> blockingQueue =
            new LinkedBlockingQueue<>(500);

    // Thread pool — 5 core threads, scales up to 20 under load
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            5, 20, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final AdapterRegistry adapterRegistry;
    private final IncomingTransactionDao incomingTransactionDao;
    private final AccountDao accountDao;
    private final CustomerDao customerDao;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public IngestionPipeline() {
        this.adapterRegistry        = new AdapterRegistry();
        this.incomingTransactionDao = new IncomingTransactionDaoImpl();
        this.accountDao             = new AccountDaoImpl();
        this.customerDao            = new CustomerDaoImpl();

        System.out.println("[IngestionPipeline] Initialised — pool ready, queue ready, " +
                           "adapters registered, DAO objects created.");
    }

    // -----------------------------------------------------------------------
    // ingest() — Submit one raw payload line for processing
    // -----------------------------------------------------------------------

    /**
     * Submit one raw payload string from a source system for ingestion.
     * Creates an IngestionWorker and submits it to the thread pool.
     *
     * @param sourceType The source system type (CBS, RTGS, SWIFT, NEFT, UPI, FINTECH)
     * @param rawPayload One raw payload string (one line from the input file)
     */
    public void ingest(SourceType sourceType, String rawPayload) {

        IngestionWorker worker = new IngestionWorker(
                sourceType,
                rawPayload,
                adapterRegistry,
                blockingQueue,
                incomingTransactionDao,
                accountDao,
                customerDao
        );

        executor.submit(worker);
        System.out.println("[IngestionPipeline] Submitted task for: " + sourceType);
    }

    // -----------------------------------------------------------------------
    // Getters
    // -----------------------------------------------------------------------

    public BlockingQueue<IncomingTransaction> getBlockingQueue() {
        return blockingQueue;
    }

    public int getQueueSize() {
        return blockingQueue.size();
    }

    // -----------------------------------------------------------------------
    // shutdown() — Clean shutdown of thread pool and connection pool
    // -----------------------------------------------------------------------

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