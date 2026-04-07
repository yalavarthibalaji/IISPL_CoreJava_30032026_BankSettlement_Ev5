package com.iispl.banksettlement.threading;

import com.iispl.banksettlement.adapter.TransactionAdapter;
import com.iispl.banksettlement.dao.IncomingTransactionDao;
import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.registry.AdapterRegistry;

import java.util.concurrent.BlockingQueue;

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

	private final SourceType sourceType;
	private final String rawPayload;
	private final AdapterRegistry adapterRegistry;
	private final BlockingQueue<IncomingTransaction> blockingQueue;
	private final IncomingTransactionDao incomingTransactionDao;

	public IngestionWorker(SourceType sourceType, String rawPayload, AdapterRegistry adapterRegistry,
			BlockingQueue<IncomingTransaction> blockingQueue, IncomingTransactionDao incomingTransactionDao) {
		this.sourceType = sourceType;
		this.rawPayload = rawPayload;
		this.adapterRegistry = adapterRegistry;
		this.blockingQueue = blockingQueue;
		this.incomingTransactionDao = incomingTransactionDao;
	}

	@Override
	public void run() {

		try {
			// duplicate check
			
			
			// adapt raw payload → IncomingTransaction
			TransactionAdapter adapter = adapterRegistry.getAdapter(sourceType);
			IncomingTransaction txn = adapter.adapt(rawPayload);

			System.out.println("Adapted txn: " + txn.getSourceRef() + " | Amount: " + txn.getAmount() + " | Source: "
					+ sourceType);

			
			
			
			// checks for duplicate transactions
			if (incomingTransactionDao.existsBySourceRef(txn.getSourceRef())) {
				System.out.println("DUPLICATE detected — skipping sourceRef: " + txn.getSourceRef());
				return;
			}

			
			incomingTransactionDao.save(txn);  // save to DB with RECEIVED status

			
			
			// update status to QUEUED
			txn.setProcessingStatus(ProcessingStatus.QUEUED);
			incomingTransactionDao.updateStatus(txn.getIncomingTxnId(), ProcessingStatus.QUEUED);

			
			
			// hand off to BlockingQueue for settlement processor
			blockingQueue.put(txn);
			System.out.println("QUEUED txn: " + txn.getSourceRef() + " | Queue size now: " + blockingQueue.size());

			
			
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			System.out.println("Thread interrupted for " + sourceType);

		} catch (IllegalArgumentException e) {
			System.out.println("Skipping line from " + sourceType + ": " + e.getMessage());

		} catch (RuntimeException e) {
			String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
			System.out.println("DB/Runtime ERROR for source [" + sourceType + "]: " + msg);

		} catch (Exception e) {
			System.out.println("Unexpected ERROR for source [" + sourceType + "]: " + e.getMessage());
			e.printStackTrace();
		}
	}
}