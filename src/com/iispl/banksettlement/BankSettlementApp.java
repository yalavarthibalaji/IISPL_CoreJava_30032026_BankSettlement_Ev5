package com.iispl.banksettlement;

import com.iispl.banksettlement.entity.NettingResult;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.service.impl.NettingServiceImpl;
import com.iispl.banksettlement.service.impl.SettlementEngineImpl;
import com.iispl.banksettlement.threading.IngestionPipeline;
import com.iispl.banksettlement.threading.TransactionDispatcher;
import com.iispl.banksettlement.dao.IncomingTransactionDao;
import com.iispl.banksettlement.dao.impl.IncomingTransactionDaoImpl;
import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.utility.CsvFileReader;
import com.iispl.banksettlement.utility.JsonFileReader;
import com.iispl.banksettlement.utility.TxtFileReader;
import com.iispl.banksettlement.utility.XlsxFileReader;
import com.iispl.banksettlement.utility.XmlFileReader;
import com.iispl.connectionpool.ConnectionPool;

import java.io.IOException;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * BankSettlementApp — Main entry point for the Bank Settlement System.
 *
 * This is a simple do-while menu that lets you run each step of the
 * settlement pipeline one at a time.
 *
 * MENU OPTIONS:
 * ─────────────────────────────────────────────────────────────────────────
 * 1. Ingest transaction files
 *    Reads all 5 test files (CBS CSV, RTGS XML, NEFT TXT, UPI JSON, Fintech JSON)
 *    Runs the IngestionPipeline → each raw payload → adapter → IncomingTransaction → DB
 *
 * 2. Dispatch transactions (SettlementProcessor)
 *    Loads all QUEUED records from DB
 *    Runs TransactionDispatcher → parses normalizedPayload → saves to credit/debit tables
 *
 * 3. Run settlement engine
 *    Loads all INITIATED credit/debit/interbank/reversal rows from DB
 *    Runs SettlementEngineImpl → settles each transaction, creates SettlementBatch records
 *
 * 4. Run netting (post-settlement)
 *    Reads all PROCESSED incoming_transaction rows
 *    Computes inter-bank bilateral net positions
 *    Prints: "Bank A → MUST PAY → Rs. X → to → Bank B"
 *    NPCI updates each bank's settlement account balance
 *
 * 5. Run full pipeline (steps 1 + 2 + 3 + 4 in sequence)
 *
 * 0. Exit
 * ─────────────────────────────────────────────────────────────────────────
 *
 * HOW TO RUN:
 *   Right-click → Run As → Java Application
 *   Use the console to type option numbers.
 *
 * PACKAGE: com.iispl.banksettlement
 */
public class BankSettlementApp {

    // Path to test files — relative to project root in Eclipse
    private static final String FILE_BASE_PATH = "src/com/iispl/banksettlement/testfiles/";

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);
        int choice = 0;

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║     BANK SETTLEMENT SYSTEM — MAIN MENU       ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        do {
            printMenu();

            System.out.print("Enter your choice: ");
            String input = scanner.nextLine().trim();

            // Validate input is a number
            if (!input.matches("[0-9]+")) {
                System.out.println("\n[App] Invalid input. Please enter a number.\n");
                continue;
            }

            choice = Integer.parseInt(input);

            switch (choice) {

                case 1:
                    runIngestion();
                    break;

                case 2:
                    runDispatch();
                    break;

                case 3:
                    runSettlement();
                    break;

                case 4:
                    runNetting();
                    break;

                case 5:
                    System.out.println("\n[App] Running FULL PIPELINE (Ingest → Dispatch → Settle → Net)...\n");
                    runIngestion();
                    runDispatch();
                    runSettlement();
                    runNetting();
                    System.out.println("\n[App] Full pipeline complete.\n");
                    break;

                case 0:
                    System.out.println("\n[App] Exiting. Closing connection pool...");
                    ConnectionPool.shutdown();
                    System.out.println("[App] Goodbye!");
                    break;

                default:
                    System.out.println("\n[App] Unknown option. Please choose 0-5.\n");
            }

        } while (choice != 0);

        scanner.close();
    }

    // -----------------------------------------------------------------------
    // Print menu
    // -----------------------------------------------------------------------

    private static void printMenu() {
        System.out.println("\n┌──────────────────────────────────────────────┐");
        System.out.println("│  MENU                                        │");
        System.out.println("│  1. Ingest transaction files                 │");
        System.out.println("│  2. Dispatch transactions (SettlementProcessor)│");
        System.out.println("│  3. Run settlement engine                    │");
        System.out.println("│  4. Run netting (post-settlement)            │");
        System.out.println("│  5. Run full pipeline (1+2+3+4)              │");
        System.out.println("│  0. Exit                                     │");
        System.out.println("└──────────────────────────────────────────────┘");
    }

    // -----------------------------------------------------------------------
    // Step 1: Ingest transaction files
    // -----------------------------------------------------------------------

    private static void runIngestion() {
        System.out.println("\n================================================");
        System.out.println("  STEP 1 — FILE INGESTION");
        System.out.println("================================================\n");

        try {
            IngestionPipeline pipeline = new IngestionPipeline();

            ingestFile(pipeline, SourceType.CBS,
                    FILE_BASE_PATH + "cbs_transactions.csv",     new CsvFileReader());

            ingestFile(pipeline, SourceType.RTGS,
                    FILE_BASE_PATH + "rtgs_transactions.xml",    new XmlFileReader());

            ingestFile(pipeline, SourceType.NEFT,
                    FILE_BASE_PATH + "neft_transactions.txt",    new TxtFileReader());

            ingestFile(pipeline, SourceType.UPI,
                    FILE_BASE_PATH + "upi_transactions.json",    new JsonFileReader("UPI_JSON"));

            ingestFile(pipeline, SourceType.FINTECH,
                    FILE_BASE_PATH + "fintech_transactions.json", new JsonFileReader("FINTECH_JSON"));

            System.out.println("\n[Ingestion] All files submitted. Waiting for workers...");
            pipeline.shutdownExecutorOnly();
            System.out.println("[Ingestion] Done. Check incoming_transaction table.\n");

        } catch (Exception e) {
            System.out.println("\n[Ingestion] ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void ingestFile(IngestionPipeline pipeline, SourceType sourceType,
                                   String filePath, com.iispl.banksettlement.utility.TransactionFileReader reader) {
        System.out.println("\n--- Reading [" + reader.getSourceFormat() + "] from: " + filePath + " ---");
        List<String> payloads;
        try {
            payloads = reader.readLines(filePath);
        } catch (IOException e) {
            System.out.println("[Ingestion] ERROR reading file: " + filePath + " | " + e.getMessage());
            return;
        }
        if (payloads.isEmpty()) {
            System.out.println("[Ingestion] WARNING — no records in: " + filePath);
            return;
        }
        System.out.println("[Ingestion] Submitting " + payloads.size() + " record(s)...");
        for (String rawPayload : payloads) {
            pipeline.ingest(sourceType, rawPayload);
        }
    }

    // -----------------------------------------------------------------------
    // Step 2: Dispatch (SettlementProcessor MODE_A)
    // -----------------------------------------------------------------------

    private static void runDispatch() {
        System.out.println("\n================================================");
        System.out.println("  STEP 2 — TRANSACTION DISPATCH");
        System.out.println("================================================\n");

        try {
            IncomingTransactionDao incomingTxnDao = new IncomingTransactionDaoImpl();
            List<IncomingTransaction> queuedTxns = incomingTxnDao.findByStatus(ProcessingStatus.QUEUED);

            if (queuedTxns.isEmpty()) {
                System.out.println("[Dispatch] No QUEUED transactions found. Run Ingestion first.");
                return;
            }

            System.out.println("[Dispatch] Found " + queuedTxns.size() + " QUEUED transaction(s). Dispatching...\n");

            BlockingQueue<IncomingTransaction> queue = new LinkedBlockingQueue<>(500);

            for (IncomingTransaction txn : queuedTxns) {
                try {
                    queue.put(txn);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("[Dispatch] Interrupted while queuing.");
                    return;
                }
            }

            // Put shutdown sentinel
            IncomingTransaction sentinel = new IncomingTransaction();
            sentinel.setSourceRef("SHUTDOWN");
            try {
                queue.put(sentinel);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Run dispatcher
            TransactionDispatcher dispatcher = new TransactionDispatcher(queue);
            Thread dispatcherThread = new Thread(dispatcher, "Dispatcher-Thread");
            dispatcherThread.start();
            try {
                dispatcherThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.println("\n[Dispatch] Done. Check credit_transaction, debit_transaction tables.\n");

        } catch (Exception e) {
            System.out.println("\n[Dispatch] ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------------
    // Step 3: Settlement Engine
    // -----------------------------------------------------------------------

    private static void runSettlement() {
        System.out.println("\n================================================");
        System.out.println("  STEP 3 — SETTLEMENT ENGINE");
        System.out.println("================================================\n");

        try {
            SettlementEngineImpl engine = new SettlementEngineImpl();
            engine.runSettlement();
            System.out.println("\n[Settlement] Done. Check settlement_batch and settlement_record tables.\n");
        } catch (Exception e) {
            System.out.println("\n[Settlement] ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------------
    // Step 4: Netting   
    //
    // -----------------------------------------------------------------------

    private static void runNetting() {
        System.out.println("\n================================================");
        System.out.println("  STEP 4 — POST-SETTLEMENT NETTING");
        System.out.println("================================================\n");

        try {
            NettingServiceImpl nettingService = new NettingServiceImpl();
            List<NettingResult> results = nettingService.runNetting();
            System.out.println("\n[Netting] Done. Total inter-bank obligations: " + results.size() + "\n");
        } catch (Exception e) {
            System.out.println("\n[Netting] ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
}