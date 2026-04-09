package com.iispl.banksettlement;

import com.iispl.banksettlement.dao.IncomingTransactionDao;
import com.iispl.banksettlement.dao.impl.IncomingTransactionDaoImpl;
import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.threading.IngestionPipeline;
import com.iispl.banksettlement.threading.TransactionDispatcher;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.utility.CsvFileReader;
import com.iispl.banksettlement.utility.JsonFileReader;
import com.iispl.banksettlement.utility.TxtFileReader;
import com.iispl.banksettlement.utility.XmlFileReader;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * SettlementProcessorTest — Phase 2 test class.
 *
 * WHAT THIS TEST DOES:
 * ─────────────────────────────────────────────────────────────────────────
 *
 * MODE A — Run from existing QUEUED records in DB (no new file ingestion): 1.
 * Calls IncomingTransactionDao.findByStatus(QUEUED) to load all already-queued
 * transactions from the DB. 2. Puts them onto a fresh BlockingQueue. 3. Starts
 * TransactionDispatcher in a background thread. 4. Dispatcher reads from queue,
 * parses normalizedPayload, builds CreditTransaction / DebitTransaction /
 * ReversalTransaction / InterBankTransaction, saves to the correct DB table,
 * marks incoming_transaction as PROCESSED.
 *
 * MODE B — Ingest NEW test files first, then dispatch (full pipeline test): 1.
 * Runs IngestionPipeline to read updated test files (CBS CSV, RTGS XML, NEFT
 * TXT, UPI JSON, Fintech JSON). 2. Each adapter converts raw payload →
 * IncomingTransaction → DB → BlockingQueue. 3. After ingestion is done, same
 * dispatcher loop processes the queue.
 *
 * HOW TO CHOOSE A MODE: Set the constant RUN_MODE below to MODE_A or MODE_B.
 *
 * PREREQUISITES: 1. Supabase project is active and db.properties is configured.
 * 2. Phase 1 schema already run (incoming_transaction, account, etc. exist). 3.
 * Phase 2 schema already run (credit_transaction, debit_transaction,
 * reversal_transaction, interbank_transaction tables exist). 4. Account INSERT
 * statements from phase2_schema.sql have been run (so debit/credit account FKs
 * can be resolved). 5. For MODE B: updated test files are in testfiles/ folder.
 *
 * EXPECTED RESULTS AFTER RUNNING: - incoming_transaction: processing_status
 * changes from QUEUED → PROCESSED (or FAILED if account not found). -
 * credit_transaction: new rows for every CREDIT incoming txn. -
 * debit_transaction: new rows for every DEBIT incoming txn. -
 * reversal_transaction: new rows for every REVERSAL incoming txn. -
 * interbank_transaction: new rows for every INTRABANK incoming txn.
 *
 */
public class SettlementProcessorTest {

	// MODE_A = load QUEUED records from DB and dispatch (no file re-ingestion)
	// MODE_B = ingest new test files first, then dispatch
	private static final String RUN_MODE = "MODE_A";

	// -----------------------------------------------------------------------
	//  Path to test files
	// -----------------------------------------------------------------------
	private static final String FILE_BASE_PATH = "src/com/iispl/banksettlement/testfiles/";

	// -----------------------------------------------------------------------
	// main
	// -----------------------------------------------------------------------

	public static void main(String[] args) {

		System.out.println("================================================");
		System.out.println("  BANK SETTLEMENT — SETTLEMENT PROCESSOR TEST");
		System.out.println("  Mode: " + RUN_MODE);
		System.out.println("================================================\n");

		if ("MODE_B".equals(RUN_MODE)) {
			runModeB();
		} else {
			runModeA();
		}
	}

	// -----------------------------------------------------------------------
	// MODE A — Load QUEUED transactions from DB, put on queue, dispatch
	// -----------------------------------------------------------------------

	private static void runModeA() {

		System.out.println("[SettlementProcessorTest] MODE A: Loading QUEUED transactions from DB...\n");

		// STEP 1: Load all QUEUED incoming transactions from DB
		IncomingTransactionDao incomingTxnDao = new IncomingTransactionDaoImpl();
		List<IncomingTransaction> queuedTxns = incomingTxnDao.findByStatus(ProcessingStatus.QUEUED);

		if (queuedTxns.isEmpty()) {
			System.out.println("[SettlementProcessorTest] No QUEUED transactions found in DB.");
			System.out.println(
					"[SettlementProcessorTest] >>> Run FileIngestionTest first to populate incoming_transaction.");
			System.out.println("[SettlementProcessorTest] >>> Or switch to MODE_B to ingest + dispatch together.");
			return;
		}

		System.out.println("[SettlementProcessorTest] Found " + queuedTxns.size()
				+ " QUEUED transaction(s) in DB. Putting them on the queue...");

		// STEP 2: Create a BlockingQueue and put all QUEUED transactions on it
		BlockingQueue<IncomingTransaction> blockingQueue = new LinkedBlockingQueue<>(500);

		for (IncomingTransaction txn : queuedTxns) {
			try {
				blockingQueue.put(txn);
				System.out.println("[SettlementProcessorTest] Queued: " + txn.getSourceRef() + " | type: "
						+ txn.getTxnType() + " | amount: " + txn.getAmount());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				System.out.println("[SettlementProcessorTest] Interrupted while queuing.");
				return;
			}
		}

		// Put shutdown sentinel so dispatcher knows when to stop
		putShutdownSignal(blockingQueue);

		// STEP 3: Start TransactionDispatcher in a background thread
		runDispatcher(blockingQueue);

		System.out.println("\n================================================");
		System.out.println("  MODE A COMPLETE");
		System.out.println("    credit_transaction    — CREDIT rows");
		System.out.println("    debit_transaction     — DEBIT rows");
		System.out.println("    reversal_transaction  — REVERSAL rows");
		System.out.println("    interbank_transaction — INTRABANK rows");
		System.out.println("    incoming_transaction  — status = PROCESSED");
		System.out.println("================================================");
	}

	// -----------------------------------------------------------------------
	// MODE B — Ingest new test files, then dispatch
	// -----------------------------------------------------------------------

	private static void runModeB() {

		System.out.println("[SettlementProcessorTest] MODE B: Ingesting test files then dispatching...\n");

		// STEP 1: Run ingestion pipeline (same as FileIngestionTest)
		IngestionPipeline pipeline = new IngestionPipeline();

		ingestFile(pipeline, SourceType.CBS, FILE_BASE_PATH + "cbs_transactions.csv", new CsvFileReader());
		ingestFile(pipeline, SourceType.RTGS, FILE_BASE_PATH + "rtgs_transactions.xml", new XmlFileReader());
		ingestFile(pipeline, SourceType.NEFT, FILE_BASE_PATH + "neft_transactions.txt", new TxtFileReader());
		ingestFile(pipeline, SourceType.UPI, FILE_BASE_PATH + "upi_transactions.json", new JsonFileReader("UPI_JSON"));
		ingestFile(pipeline, SourceType.FINTECH, FILE_BASE_PATH + "fintech_transactions.json",
				new JsonFileReader("FINTECH_JSON"));

		System.out.println("\n[SettlementProcessorTest] Ingestion submitted. Waiting for workers...");

		// STEP 2: Wait for all ingestion workers to finish, but do NOT close
		// ConnectionPool yet.
		// We call shutdownExecutorOnly() which shuts down the ThreadPoolExecutor
		pipeline.shutdownExecutorOnly();

		System.out.println("[SettlementProcessorTest] Ingestion complete. Getting queue from pipeline...");

		// STEP 3: Get the BlockingQueue that the pipeline populated
		BlockingQueue<IncomingTransaction> blockingQueue = pipeline.getBlockingQueue();

		System.out.println("[SettlementProcessorTest] Queue size after ingestion: " + blockingQueue.size());

		// Put shutdown sentinel
		putShutdownSignal(blockingQueue);

		// STEP 4: Dispatch
		runDispatcher(blockingQueue);

		System.out.println("\n================================================");
		System.out.println("  MODE B COMPLETE");
		System.out.println("  Check Supabase tables for new rows.");
		System.out.println("================================================");
	}

	// -----------------------------------------------------------------------
	// runDispatcher — starts TransactionDispatcher thread and waits for it
	// -----------------------------------------------------------------------

	private static void runDispatcher(BlockingQueue<IncomingTransaction> blockingQueue) {

		System.out.println("\n[SettlementProcessorTest] Starting TransactionDispatcher...\n");

		TransactionDispatcher dispatcher = new TransactionDispatcher(blockingQueue);
		Thread dispatcherThread = new Thread(dispatcher, "SettlementDispatcher-1");
		dispatcherThread.start();

		try {
			// Wait for the dispatcher thread to finish (it stops when it sees SHUTDOWN
			// signal)
			dispatcherThread.join();
			System.out.println("\n[SettlementProcessorTest] Dispatcher thread finished.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			System.out.println("[SettlementProcessorTest] Main thread interrupted while waiting.");
		}
	}

	// -----------------------------------------------------------------------
	// putShutdownSignal — puts a sentinel IncomingTransaction on the queue
	// that tells the dispatcher to stop its loop.
	// -----------------------------------------------------------------------

	private static void putShutdownSignal(BlockingQueue<IncomingTransaction> queue) {
		IncomingTransaction sentinel = new IncomingTransaction();
		sentinel.setSourceRef("SHUTDOWN");
		try {
			queue.put(sentinel);
			System.out.println("[SettlementProcessorTest] Shutdown signal added to queue.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// -----------------------------------------------------------------------
	// ingestFile — helper used in MODE B (same logic as FileIngestionTest)
	// -----------------------------------------------------------------------

	private static void ingestFile(IngestionPipeline pipeline, SourceType sourceType, String filePath,
			com.iispl.banksettlement.utility.TransactionFileReader reader) {

		System.out.println("\n--- Reading [" + reader.getSourceFormat() + "] from: " + filePath + " ---");

		List<String> payloads;

		try {
			payloads = reader.readLines(filePath);
		} catch (IOException e) {
			System.out.println("[SettlementProcessorTest] ERROR reading file [" + filePath + "]: " + e.getMessage());
			return;
		}

		if (payloads.isEmpty()) {
			System.out.println("[SettlementProcessorTest] WARNING — no records found in: " + filePath);
			return;
		}

		System.out.println("[SettlementProcessorTest] Submitting " + payloads.size() + " record(s) from ["
				+ reader.getSourceFormat() + "]...");

		for (String rawPayload : payloads) {
			pipeline.ingest(sourceType, rawPayload);
		}
	}
}
