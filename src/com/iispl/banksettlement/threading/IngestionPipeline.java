package com.iispl.banksettlement.threading;

import com.iispl.banksettlement.dao.IncomingTransactionDao;
import com.iispl.banksettlement.dao.impl.IncomingTransactionDaoImpl;
import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.registry.AdapterRegistry;
import com.iispl.banksettlement.utility.PhaseLogger;
import com.iispl.connectionpool.ConnectionPool;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * IngestionPipeline — creates and submits IngestionWorker tasks.
 *
 * Holds the shared infrastructure: AdapterRegistry, BlockingQueue,
 * ThreadPoolExecutor, and IncomingTransactionDao.
 *
 * CHANGE LOG (v3 — shutdownExecutorOnly added): - shutdownExecutorOnly() added
 * for use by SettlementProcessorTest MODE_B. This shuts down the
 * ThreadPoolExecutor (stops ingestion workers) WITHOUT closing the
 * ConnectionPool. The dispatcher thread that runs AFTER ingestion still needs
 * the ConnectionPool open to do its DB saves. - The original shutdown() method
 * still closes the ConnectionPool — use it in FileIngestionTest where no
 * dispatcher runs after.
 */
public class IngestionPipeline {
    private static final Logger LOGGER = Logger.getLogger(IngestionPipeline.class.getName());

	private final BlockingQueue<IncomingTransaction> blockingQueue = new LinkedBlockingQueue<>(500);

	private final ThreadPoolExecutor executor = new ThreadPoolExecutor(5, 20, 60L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(100), new ThreadPoolExecutor.CallerRunsPolicy());

	private final AdapterRegistry adapterRegistry;
	private final IncomingTransactionDao incomingTransactionDao;
    private final ConcurrentHashMap<SourceType, SourceStats> statsBySource = new ConcurrentHashMap<>();

	public IngestionPipeline() {
		this.adapterRegistry = new AdapterRegistry();
		this.incomingTransactionDao = new IncomingTransactionDaoImpl();

        PhaseLogger.getLogger().info("Ingestion pipeline initialized.");
	}

	/**
	 * Submits one raw payload for the given source type to the thread pool. The
	 * IngestionWorker will adapt, deduplicate, save, and queue the transaction.
	 *
	 * @param sourceType the source system this payload came from
	 * @param rawPayload the raw string payload (pipe-delimited, XML, fixed-width,
	 *                   or JSON)
	 */
	public void ingest(SourceType sourceType, String rawPayload) {
        statsBySource.computeIfAbsent(sourceType, k -> new SourceStats()).submitted.incrementAndGet();

		IngestionWorker worker = new IngestionWorker(sourceType, rawPayload, adapterRegistry, blockingQueue,
				incomingTransactionDao, statsBySource.computeIfAbsent(sourceType, k -> new SourceStats()));

		executor.submit(worker);
	}

	public BlockingQueue<IncomingTransaction> getBlockingQueue() {
		return blockingQueue;
	}

	public int getQueueSize() {
		return blockingQueue.size();
	}

	/**
	 * Shuts down only the ThreadPoolExecutor — stops accepting new ingestion tasks
	 * and waits for existing workers to finish.
	 *
	 * DOES NOT close the ConnectionPool.
	 *
	 * USE THIS in SettlementProcessorTest MODE_B: After ingestion workers are done,
	 * the dispatcher thread still needs the ConnectionPool open to save
	 * transactions to their subtype tables. Calling shutdown() here would close the
	 * pool too early.
	 */
	public void shutdownExecutorOnly() {
        PhaseLogger.getLogger().info("Shutting down ingestion workers...");

		executor.shutdown();

		try {
			boolean finished = executor.awaitTermination(60, TimeUnit.SECONDS);

			if (!finished) {
                PhaseLogger.getLogger().warning("Timeout while waiting workers. Forcing shutdown.");
				executor.shutdownNow();
				executor.awaitTermination(10, TimeUnit.SECONDS);
			}

            PhaseLogger.getLogger().info("Ingestion workers stopped. Queue size: " + blockingQueue.size());
            PhaseLogger.getLogger().info(buildSummaryReport());

		} catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Gracefully shuts down both the ThreadPoolExecutor AND the ConnectionPool.
	 *
	 * USE THIS in FileIngestionTest — where no dispatcher runs after ingestion. Do
	 * NOT use this in SettlementProcessorTest MODE_B (use shutdownExecutorOnly
	 * instead).
	 */
	public void shutdown() {
        PhaseLogger.getLogger().info("Shutting down ingestion pipeline...");

		executor.shutdown();

		try {
			boolean finished = executor.awaitTermination(60, TimeUnit.SECONDS);

			if (!finished) {
                PhaseLogger.getLogger().warning("Timeout while waiting workers. Forcing shutdown.");
				executor.shutdownNow();
				executor.awaitTermination(10, TimeUnit.SECONDS);
			}

            PhaseLogger.getLogger().info("Ingestion worker shutdown complete.");
            PhaseLogger.getLogger().info(buildSummaryReport());

		} catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();

		} finally {
			// Only close the pool AFTER all worker threads are fully stopped
			ConnectionPool.shutdown();
            PhaseLogger.getLogger().info("ConnectionPool closed.");
		}
	}

    public String buildSummaryReport() {
        StringBuilder sb = new StringBuilder("Ingestion summary by source:");
        for (SourceType sourceType : SourceType.values()) {
            SourceStats stats = statsBySource.get(sourceType);
            if (stats == null) {
                continue;
            }
            sb.append(" ").append(sourceType.name())
              .append("[read=").append(stats.submitted.get())
              .append(", adapted=").append(stats.adapted.get())
              .append(", queued=").append(stats.queued.get())
              .append(", duplicate=").append(stats.duplicate.get())
              .append(", rejected=").append(stats.rejected.get())
              .append(", failed=").append(stats.failed.get())
              .append("];");
        }
        return sb.toString();
    }

    static final class SourceStats {
        final AtomicInteger submitted = new AtomicInteger();
        final AtomicInteger adapted = new AtomicInteger();
        final AtomicInteger queued = new AtomicInteger();
        final AtomicInteger duplicate = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
    }
}