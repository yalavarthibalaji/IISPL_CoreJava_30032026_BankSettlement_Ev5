package com.iispl.threading;

import com.iispl.adapter.TransactionAdapter;
import com.iispl.dao.IncomingTransactionDao;
import com.iispl.entity.IncomingTransaction;
import com.iispl.enums.ProcessingStatus;
import com.iispl.enums.SourceType;
import com.iispl.registry.AdapterRegistry;

import java.util.concurrent.BlockingQueue;
import java.util.logging.Logger;

/**
 * IngestionWorker — Runnable that adapts one raw payload and puts it on the
 * BlockingQueue for downstream settlement processing.
 *
 * RESPONSIBILITY: 1. Pick the correct adapter from AdapterRegistry by
 * SourceType. 2. Call adapter.adapt(rawPayload) → IncomingTransaction. 3. Check
 * for duplicates (existsBySourceRef). 4. Save to DB with status RECEIVED. 5.
 * Update status to QUEUED. 6. Put IncomingTransaction onto the BlockingQueue.
 *
 * CHANGE LOG (v2): - AccountDao and CustomerDao dependencies REMOVED.
 * Account/customer validation has moved to the settlement phase. The settlement
 * engine reads debitAccount and creditAccount from normalizedPayload and
 * validates them when building Transaction objects.
 *
 * - validateAccountsAndCustomers() method REMOVED entirely.
 *
 * - All transactions now follow a single path: RECEIVED → QUEUED (unless
 * duplicate, in which case they are silently skipped). There is no FAILED path
 * in the ingestion phase anymore.
 *
 * - The log line no longer prints debit/credit account numbers or
 * requiresAccountValidation (all removed from IncomingTransaction). Account
 * info is now only inside normalizedPayload.
 */
public class IngestionWorker implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(IngestionWorker.class.getName());

	private final SourceType sourceType;
	private final String rawPayload;
	private final AdapterRegistry adapterRegistry;
	private final BlockingQueue<IncomingTransaction> blockingQueue;
	private final IncomingTransactionDao incomingTransactionDao;
    private final IngestionPipeline.SourceStats sourceStats;

	public IngestionWorker(SourceType sourceType, String rawPayload, AdapterRegistry adapterRegistry,
			BlockingQueue<IncomingTransaction> blockingQueue, IncomingTransactionDao incomingTransactionDao,
            IngestionPipeline.SourceStats sourceStats) {
		this.sourceType = sourceType;
		this.rawPayload = rawPayload;
		this.adapterRegistry = adapterRegistry;
		this.blockingQueue = blockingQueue;
		this.incomingTransactionDao = incomingTransactionDao;
        this.sourceStats = sourceStats;
	}

	@Override
	public void run() {

		try {
			// duplicate check
			
			
			// adapt raw payload → IncomingTransaction
			TransactionAdapter adapter = adapterRegistry.getAdapter(sourceType);
			IncomingTransaction txn = adapter.adapt(rawPayload);
            sourceStats.adapted.incrementAndGet();

			
			
			
			// checks for duplicate transactions
			if (incomingTransactionDao.existsBySourceRef(txn.getSourceRef())) {
                sourceStats.duplicate.incrementAndGet();
				return;
			}

			
			incomingTransactionDao.save(txn);  // save to DB with RECEIVED status

			
			
			// update status to QUEUED
			txn.setProcessingStatus(ProcessingStatus.QUEUED);
			incomingTransactionDao.updateStatus(txn.getIncomingTxnId(), ProcessingStatus.QUEUED);

			
			
			// hand off to BlockingQueue for settlement processor
			blockingQueue.put(txn);
            sourceStats.queued.incrementAndGet();

			
			
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
            sourceStats.failed.incrementAndGet();
            LOGGER.warning("Ingestion worker interrupted for " + sourceType.name());

		} catch (IllegalArgumentException e) {
            sourceStats.rejected.incrementAndGet();
            LOGGER.fine("Rejected payload from " + sourceType.name() + ": " + e.getMessage());

		} catch (RuntimeException e) {
			String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            sourceStats.failed.incrementAndGet();
            LOGGER.warning("DB/runtime error for source [" + sourceType + "]: " + msg);

		} catch (Exception e) {
            sourceStats.failed.incrementAndGet();
            LOGGER.warning("Unexpected error for source [" + sourceType + "]: " + e.getMessage());
		}
	}
}