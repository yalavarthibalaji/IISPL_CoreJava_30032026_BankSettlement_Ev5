package com.iispl.banksettlement;

import com.iispl.banksettlement.dao.IncomingTransactionDao;
import com.iispl.banksettlement.dao.impl.IncomingTransactionDaoImpl;
import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.entity.NettingPosition;
import com.iispl.banksettlement.entity.ReconciliationEntry;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.service.impl.NettingServiceImpl;
import com.iispl.banksettlement.service.impl.ReconciliationServiceImpl;
import com.iispl.banksettlement.service.impl.SettlementEngineImpl;
import com.iispl.banksettlement.threading.IngestionPipeline;
import com.iispl.banksettlement.threading.TransactionDispatcher;
import com.iispl.banksettlement.utility.CsvFileReader;
import com.iispl.banksettlement.utility.JsonFileReader;
import com.iispl.banksettlement.utility.TxtFileReader;
import com.iispl.banksettlement.utility.XmlFileReader;
import com.iispl.connectionpool.ConnectionPool;

import java.io.IOException;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

//
/**
 * BankSettlementApp — Main entry point for the Bank Settlement System.
 *
 * Simple do-while menu to run each step of the pipeline one at a time.
 *
 * MENU:
 * ─────────────────────────────────────────────────────────────────────────
 * 1. Ingest transaction files
 *    Reads CBS CSV, RTGS XML, NEFT TXT, UPI JSON, Fintech JSON.
 *    Saves IncomingTransaction rows to DB.
 *
 * 2. Dispatch transactions
 *    Loads QUEUED rows from incoming_transaction table.
 *    Saves to credit_transaction / debit_transaction / interbank / reversal tables.
 *    Now also saves fromBank, toBank, incomingTxnId.
 *
 * 3. Run settlement engine
 *    Loads INITIATED rows from all 4 transaction tables.
 *    Creates settlement_batch and settlement_record rows.
 *    Updates balances in account table.
 *
 * 4. Run netting engine
 *    Reads PROCESSED incoming_transaction rows.
 *    Computes bilateral net positions per bank pair.
 *    Saves netting_position rows.
 *    Updates npci_bank_account balances.
 *    Prints: "Bank A → MUST PAY → Rs.X → TO → Bank B".
 *
 * 5. Run reconciliation
 *    For each NPCI bank account:
 *      Computes expected balance from opening_balance ± net positions.
 *      Compares to actual current_balance in DB.
 *      Saves reconciliation_entry (MATCHED or UNMATCHED).
 *      Prints the reconciliation report.
 *
 * 6. Run full pipeline (steps 1 → 2 → 3 → 4 → 5 in sequence)
 *
 * 0. Exit
 * ─────────────────────────────────────────────────────────────────────────
 */
public class BankSettlementApp {

    // Path to test files — relative to Eclipse project root
    private static final String FILE_BASE_PATH =
            "src/com/iispl/banksettlement/testfiles/";

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);
        int choice = -1;

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║     BANK SETTLEMENT SYSTEM — MAIN MENU       ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        do {
            printMenu();
            System.out.print("Enter your choice: ");
            String input = scanner.nextLine().trim();

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
                    runReconciliation();
                    break;
                case 6:
                    System.out.println("\n[App] Running FULL PIPELINE...\n");
                    runIngestion();
                    runDispatch();
                    runSettlement();
                    runNetting();
                    runReconciliation();
                    System.out.println("\n[App] Full pipeline complete.\n");
                    break;
                case 0:
                    System.out.println("\n[App] Exiting. Closing connection pool...");
                    ConnectionPool.shutdown();
                    System.out.println("[App] Goodbye!");
                    break;
                default:
                    System.out.println("\n[App] Unknown option. Please choose 0-6.\n");
            }

        } while (choice != 0);

        scanner.close();
    }

    // -----------------------------------------------------------------------
    // Menu
    // -----------------------------------------------------------------------

    private static void printMenu() {
        System.out.println("\n┌──────────────────────────────────────────────┐");
        System.out.println("│  MENU                                        │");
        System.out.println("│  1. Ingest transaction files                 │");
        System.out.println("│  2. Dispatch transactions                    │");
        System.out.println("│  3. Run settlement engine                    │");
        System.out.println("│  4. Run netting engine                       │");
        System.out.println("│  5. Run reconciliation                       │");
        System.out.println("│  6. Run full pipeline (1+2+3+4+5)            │");
        System.out.println("│  0. Exit                                     │");
        System.out.println("└──────────────────────────────────────────────┘");
    }

    // -----------------------------------------------------------------------
    // Step 1: Ingest
    // -----------------------------------------------------------------------

    private static void runIngestion() {
        System.out.println("\n================================================");
        System.out.println("  STEP 1 — FILE INGESTION");
        System.out.println("================================================\n");
        try {
            IngestionPipeline pipeline = new IngestionPipeline();

            ingestFile(pipeline, SourceType.CBS,
                    FILE_BASE_PATH + "cbs_transactions.csv",      new CsvFileReader());
            ingestFile(pipeline, SourceType.RTGS,
                    FILE_BASE_PATH + "rtgs_transactions.xml",     new XmlFileReader());
            ingestFile(pipeline, SourceType.NEFT,
                    FILE_BASE_PATH + "neft_transactions.txt",     new TxtFileReader());
            ingestFile(pipeline, SourceType.UPI,
                    FILE_BASE_PATH + "upi_transactions.json",     new JsonFileReader("UPI_JSON"));
            ingestFile(pipeline, SourceType.FINTECH,
                    FILE_BASE_PATH + "fintech_transactions.json", new JsonFileReader("FINTECH_JSON"));

            System.out.println("\n[Ingestion] All files submitted. Waiting for workers...");
            pipeline.shutdownExecutorOnly();
            System.out.println("[Ingestion] Done.\n");

        } catch (Exception e) {
            System.out.println("\n[Ingestion] ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void ingestFile(IngestionPipeline pipeline, SourceType sourceType,
            String filePath, com.iispl.banksettlement.utility.TransactionFileReader reader) {
        System.out.println("--- Reading [" + reader.getSourceFormat() + "] from: " + filePath + " ---");
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
    // Step 2: Dispatch
    // -----------------------------------------------------------------------

    private static void runDispatch() {
        System.out.println("\n================================================");
        System.out.println("  STEP 2 — TRANSACTION DISPATCH");
        System.out.println("================================================\n");
        try {
            IncomingTransactionDao dao   = new IncomingTransactionDaoImpl();
            List<IncomingTransaction> txns = dao.findByStatus(ProcessingStatus.QUEUED);

            if (txns.isEmpty()) {
                System.out.println("[Dispatch] No QUEUED transactions found. Run Ingestion first.");
                return;
            }

            System.out.println("[Dispatch] Found " + txns.size() + " QUEUED transaction(s).");
            BlockingQueue<IncomingTransaction> queue = new LinkedBlockingQueue<>(500);

            for (IncomingTransaction txn : txns) {
                try { queue.put(txn); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); return;
                }
            }

            // Shutdown sentinel
            IncomingTransaction sentinel = new IncomingTransaction();
            sentinel.setSourceRef("SHUTDOWN");
            try { queue.put(sentinel); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            TransactionDispatcher dispatcher = new TransactionDispatcher(queue);
            Thread t = new Thread(dispatcher, "Dispatcher-Thread");
            t.start();
            try { t.join(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.println("\n[Dispatch] Done.\n");

        } catch (Exception e) {
            System.out.println("\n[Dispatch] ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------------
    // Step 3: Settlement
    // -----------------------------------------------------------------------

    private static void runSettlement() {
        System.out.println("\n================================================");
        System.out.println("  STEP 3 — SETTLEMENT ENGINE");
        System.out.println("================================================\n");
        try {
            SettlementEngineImpl engine = new SettlementEngineImpl();
            engine.runSettlement();
            System.out.println("\n[Settlement] Done.\n");
        } catch (Exception e) {
            System.out.println("\n[Settlement] ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------------
    // Step 4: Netting
    // -----------------------------------------------------------------------

    private static void runNetting() {
        System.out.println("\n================================================");
        System.out.println("  STEP 4 — NETTING ENGINE");
        System.out.println("================================================\n");
        try {
            NettingServiceImpl nettingService = new NettingServiceImpl();
            List<NettingPosition> positions = nettingService.runNetting();
            System.out.println("\n[Netting] Done. Positions computed: " + positions.size() + "\n");
        } catch (Exception e) {
            System.out.println("\n[Netting] ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------------
    // Step 5: Reconciliation
    // -----------------------------------------------------------------------

    private static void runReconciliation() {
        System.out.println("\n================================================");
        System.out.println("  STEP 5 — RECONCILIATION");
        System.out.println("================================================\n");
        try {
            ReconciliationServiceImpl reconService = new ReconciliationServiceImpl();
            List<ReconciliationEntry> entries = reconService.runReconciliation();
            System.out.println("\n[Reconciliation] Done. Entries created: " + entries.size() + "\n");
        } catch (Exception e) {
            System.out.println("\n[Reconciliation] ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
}