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
import com.iispl.banksettlement.utility.PhaseLogger;
import com.iispl.banksettlement.utility.TxtFileReader;
import com.iispl.banksettlement.utility.XmlFileReader;
import com.iispl.connectionpool.ConnectionPool;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    private static final Logger APP_LOGGER = Logger.getLogger(BankSettlementApp.class.getName());

    public static void main(String[] args) {
        System.setProperty("org.slf4j.simpleLogger.log.com.zaxxer.hikari", "warn");
        PhaseLogger.configureReadableConsole(APP_LOGGER);
        Logger.getLogger("com.zaxxer.hikari").setLevel(Level.WARNING);

        Scanner scanner = new Scanner(System.in);
        int choice = -1;

        APP_LOGGER.info("BANK SETTLEMENT SYSTEM - MAIN MENU");

        do {
            printMenu();
            System.out.print("Enter your choice: ");
            String input = scanner.nextLine().trim();

            if (!input.matches("[0-9]+")) {
                APP_LOGGER.warning("Invalid input. Please enter a number.");
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
                    APP_LOGGER.info("Running FULL PIPELINE...");
                    runIngestion();
                    runDispatch();
                    runSettlement();
                    runNetting();
                    runReconciliation();
                    APP_LOGGER.info("Full pipeline complete.");
                    break;
                case 0:
                    APP_LOGGER.info("Exiting. Closing connection pool...");
                    ConnectionPool.shutdown();
                    APP_LOGGER.info("Goodbye!");
                    break;
                default:
                    APP_LOGGER.warning("Unknown option. Please choose 0-6.");
            }

        } while (choice != 0);

        scanner.close();
    }

    // -----------------------------------------------------------------------
    // Menu
    // -----------------------------------------------------------------------

    private static void printMenu() {
        APP_LOGGER.info("MENU: 1-Ingestion, 2-Dispatch, 3-Settlement, 4-Netting, 5-Reconciliation, 6-Full Pipeline, 0-Exit");
    }

    // -----------------------------------------------------------------------
    // Step 1: Ingest
    // -----------------------------------------------------------------------

    private static void runIngestion() {
        try (PhaseLogger.PhaseLogContext ignored = PhaseLogger.startPhase("phase1_ingestion", "STEP 1 - FILE INGESTION")) {
            Logger logger = PhaseLogger.getLogger();
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

            logger.info("All files submitted. Waiting for workers to finish.");
            pipeline.shutdownExecutorOnly();
            logger.info("Ingestion completed successfully.");

        } catch (Exception e) {
            PhaseLogger.getLogger().log(Level.SEVERE, "Ingestion failed: " + e.getMessage(), e);
        }
    }

    private static void ingestFile(IngestionPipeline pipeline, SourceType sourceType,
            String filePath, com.iispl.banksettlement.utility.TransactionFileReader reader) {
        Logger logger = PhaseLogger.getLogger();
        logger.info("Reading " + reader.getSourceFormat() + " file: " + filePath);
        List<String> payloads;
        try {
            payloads = reader.readLines(filePath);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error reading file: " + filePath + " | " + e.getMessage(), e);
            return;
        }
        if (payloads.isEmpty()) {
            logger.warning("No records found in file: " + filePath);
            return;
        }
        logger.info("Submitting " + payloads.size() + " records from " + sourceType.name() + ".");
        for (String rawPayload : payloads) {
            pipeline.ingest(sourceType, rawPayload);
        }
    }

    // -----------------------------------------------------------------------
    // Step 2: Dispatch
    // -----------------------------------------------------------------------

    private static void runDispatch() {
        try (PhaseLogger.PhaseLogContext ignored = PhaseLogger.startPhase("phase2_dispatch", "STEP 2 - TRANSACTION DISPATCH")) {
            Logger logger = PhaseLogger.getLogger();
            int queuedInIncomingTable = countByStatus("incoming_transaction", "processing_status", "QUEUED");
            logger.info("Incoming QUEUED records before dispatch: " + queuedInIncomingTable);
            IncomingTransactionDao dao   = new IncomingTransactionDaoImpl();
            List<IncomingTransaction> txns = dao.findByStatus(ProcessingStatus.QUEUED);

            if (txns.isEmpty()) {
                logger.info("No QUEUED transactions found. Run ingestion first.");
                return;
            }

            logger.info("Found " + txns.size() + " QUEUED transactions.");
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

            int initiatedCredits = countByStatus("credit_transaction", "status", "INITIATED");
            int initiatedDebits = countByStatus("debit_transaction", "status", "INITIATED");
            int initiatedInterbank = countByStatus("interbank_transaction", "status", "INITIATED");
            int initiatedReversal = countByStatus("reversal_transaction", "status", "INITIATED");
            logger.info("Dispatch output -> CREDIT: " + initiatedCredits + ", DEBIT: " + initiatedDebits
                    + ", INTERBANK: " + initiatedInterbank + ", REVERSAL: " + initiatedReversal);
            logger.info("Dispatch completed successfully.");

        } catch (Exception e) {
            PhaseLogger.getLogger().log(Level.SEVERE, "Dispatch failed: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Step 3: Settlement
    // -----------------------------------------------------------------------

    private static void runSettlement() {
        try (PhaseLogger.PhaseLogContext ignored = PhaseLogger.startPhase("phase3_settlement", "STEP 3 - SETTLEMENT ENGINE")) {
            Logger logger = PhaseLogger.getLogger();
            SettlementEngineImpl engine = new SettlementEngineImpl();
            engine.runSettlement();
            logger.info("Settlement completed successfully.");
        } catch (Exception e) {
            PhaseLogger.getLogger().log(Level.SEVERE, "Settlement failed: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Step 4: Netting
    // -----------------------------------------------------------------------

    private static void runNetting() {
        try (PhaseLogger.PhaseLogContext ignored = PhaseLogger.startPhase("phase4_netting", "STEP 4 - NETTING ENGINE")) {
            Logger logger = PhaseLogger.getLogger();
            NettingServiceImpl nettingService = new NettingServiceImpl();
            List<NettingPosition> positions = nettingService.runNetting();
            logger.info("Netting completed. Positions computed: " + positions.size());
        } catch (Exception e) {
            PhaseLogger.getLogger().log(Level.SEVERE, "Netting failed: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Step 5: Reconciliation
    // -----------------------------------------------------------------------

    private static void runReconciliation() {
        try (PhaseLogger.PhaseLogContext ignored = PhaseLogger.startPhase("phase5_reconciliation", "STEP 5 - RECONCILIATION")) {
            Logger logger = PhaseLogger.getLogger();
            ReconciliationServiceImpl reconService = new ReconciliationServiceImpl();
            List<ReconciliationEntry> entries = reconService.runReconciliation();
            logger.info("Reconciliation completed. Entries created: " + entries.size());
        } catch (Exception e) {
            PhaseLogger.getLogger().log(Level.SEVERE, "Reconciliation failed: " + e.getMessage(), e);
        }
    }

    private static int countByStatus(String tableName, String statusColumn, String status) {
        String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE " + statusColumn + " = ?";
        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (Exception e) {
            APP_LOGGER.warning("Could not count status for table " + tableName + ": " + e.getMessage());
        }
        return 0;
    }
}