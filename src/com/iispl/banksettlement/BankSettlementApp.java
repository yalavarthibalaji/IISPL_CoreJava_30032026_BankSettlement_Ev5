package com.iispl.banksettlement;

import com.iispl.banksettlement.dao.AccountDao;
import com.iispl.banksettlement.dao.CustomerDao;
import com.iispl.banksettlement.dao.IncomingTransactionDao;
import com.iispl.banksettlement.dao.SettlementBatchDao;
import com.iispl.banksettlement.dao.impl.AccountDaoImpl;
import com.iispl.banksettlement.dao.impl.CustomerDaoImpl;
import com.iispl.banksettlement.dao.impl.IncomingTransactionDaoImpl;
import com.iispl.banksettlement.dao.impl.SettlementBatchDaoImpl;
import com.iispl.banksettlement.entity.Account;
import com.iispl.banksettlement.entity.Customer;
import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.entity.SettlementBatch;
import com.iispl.banksettlement.entity.SettlementRecord;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.service.SettlementEngine;
import com.iispl.banksettlement.threading.IngestionPipeline;
import com.iispl.banksettlement.threading.TransactionDispatcher;
import com.iispl.banksettlement.enums.SourceType;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BankSettlementApp — Main UI (console menu) for the Bank Settlement System.
 *
 * WHY NOT calling FileIngestionTest.main() / SettlementEngineTest.main() directly?
 *   Because those classes call ConnectionPool.shutdown() inside them.
 *   If we call them from here, the pool closes and options 4-7 (query menu)
 *   will crash with "pool is closed" error.
 *   So we copy only the CORE logic here, without the shutdown call.
 *
 * PACKAGE: com.iispl.banksettlement
 */
public class BankSettlementApp {

  

    // Path to test files — same as FileIngestionTest
    private static final String FILE_BASE_PATH =
            "src/com/iispl/banksettlement/testfiles/";

    private static final Scanner               scanner     = new Scanner(System.in);
    private static final AccountDao            accountDao  = new AccountDaoImpl();
    private static final CustomerDao           customerDao = new CustomerDaoImpl();
    private static final IncomingTransactionDao incomingDao = new IncomingTransactionDaoImpl();
    private static final SettlementBatchDao    batchDao    = new SettlementBatchDaoImpl();

    // -----------------------------------------------------------------------
    // MAIN
    // -----------------------------------------------------------------------

    public static void main(String[] args) {

        System.out.println("=================================================");
        System.out.println("   IISPL BANK SETTLEMENT APPLICATION  v5.0");
        System.out.println("   CBS | RTGS | NEFT | UPI | FINTECH");
        System.out.println("=================================================");

        boolean running = true;
        while (running) {
            printMenu();
            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1": runFileIngestion();        break;
                case "2": runDispatch();             break;
                case "3": runSettlementEngine();     break;
                case "4": viewIncomingTransactions();break;
                case "5": viewSettlementBatch();     break;
                case "6": checkAccount();            break;
                case "7": checkCustomer();           break;
                case "0": running = false;           break;
                default:  System.out.println("  Invalid choice. Enter 0-7.");
            }
        }

        // Close pool ONCE here at the very end when user exits
        ConnectionPool.shutdown();
        System.out.println("  Connection pool closed. Goodbye!");
        scanner.close();
    }

    // -----------------------------------------------------------------------
    // MENU
    // -----------------------------------------------------------------------

    private static void printMenu() {
        System.out.println();
        System.out.println("  -------- MAIN MENU ----------------------------");
        System.out.println("  PIPELINE (run in this order):");
        System.out.println("   1. File Ingestion    (test files  -> incoming_transaction)");
        System.out.println("   2. Dispatch          (incoming    -> credit/debit tables)");
        System.out.println("   3. Settlement Engine (INITIATED   -> SETTLED)");
        System.out.println("  QUERY:");
        System.out.println("   4. View Incoming Transactions (filter by status)");
        System.out.println("   5. View Settlement Batch + Records");
        System.out.println("   6. Check Account");
        System.out.println("   7. Check Customer KYC Status");
        System.out.println("   0. Exit");
        System.out.println("  -----------------------------------------------");
        System.out.print("  Enter choice: ");
    }

    // -----------------------------------------------------------------------
    // OPTION 1 — File Ingestion
    //
    // NOTE: We do NOT call FileIngestionTest.main() because it calls
    //       pipeline.shutdown() which closes ConnectionPool.
    //       Instead we replicate only the ingestion logic here.
    // -----------------------------------------------------------------------

    private static void runFileIngestion() {
        System.out.println("\n  [Ingestion] Reading test files -> incoming_transaction...\n");

        // Create a fresh pipeline (does NOT close ConnectionPool on its own)
        IngestionPipeline pipeline = new IngestionPipeline();

        ingestFile(pipeline, SourceType.CBS,
                FILE_BASE_PATH + "cbs_transactions.csv",    new CsvFileReader());

        ingestFile(pipeline, SourceType.RTGS,
                FILE_BASE_PATH + "rtgs_transactions.xml",   new XmlFileReader());

        ingestFile(pipeline, SourceType.NEFT,
                FILE_BASE_PATH + "neft_transactions.txt",   new TxtFileReader());

        ingestFile(pipeline, SourceType.UPI,
                FILE_BASE_PATH + "upi_transactions.json",   new JsonFileReader("UPI_JSON"));

        ingestFile(pipeline, SourceType.FINTECH,
                FILE_BASE_PATH + "fintech_transactions.json", new JsonFileReader("FINTECH_JSON"));

        System.out.println("\n  [Ingestion] Waiting for all workers to finish...");

        // shutdownExecutorOnly() waits for workers but does NOT close ConnectionPool
        pipeline.shutdownExecutorOnly();

        System.out.println("  [Ingestion] Done. Check DB -> incoming_transaction table.");
    }

    // Helper used by runFileIngestion()
    private static void ingestFile(IngestionPipeline pipeline,
                                   SourceType sourceType,
                                   String filePath,
                                   com.iispl.banksettlement.utility.TransactionFileReader reader) {
        System.out.println("  Reading [" + reader.getSourceFormat() + "] from: " + filePath);
        List<String> payloads;
        try {
            payloads = reader.readLines(filePath);
        } catch (IOException e) {
            System.out.println("  [ERROR] Cannot read file: " + filePath + " -> " + e.getMessage());
            return;
        }
        if (payloads.isEmpty()) {
            System.out.println("  [WARN] No records found in: " + filePath);
            return;
        }
        System.out.println("  Submitting " + payloads.size() + " record(s)...");
        for (String rawPayload : payloads) {
            pipeline.ingest(sourceType, rawPayload);
        }
    }

    // -----------------------------------------------------------------------
    // OPTION 2 — Dispatch
    //
    // NOTE: We do NOT call SettlementProcessorTest.main() because its
    //       runModeA() and runModeB() are private static — not accessible.
    //       We replicate MODE_A logic here (load QUEUED from DB and dispatch).
    // -----------------------------------------------------------------------

    private static void runDispatch() {
        System.out.println("\n  [Dispatch] Loading QUEUED transactions and dispatching...\n");

        // Step 1: Load all QUEUED incoming transactions from DB
        List<IncomingTransaction> queuedTxns = incomingDao.findByStatus(ProcessingStatus.QUEUED);

        if (queuedTxns.isEmpty()) {
            System.out.println("  [Dispatch] No QUEUED transactions found.");
            System.out.println("  [Dispatch] Run option 1 (File Ingestion) first.");
            return;
        }

        System.out.println("  [Dispatch] Found " + queuedTxns.size() + " QUEUED transaction(s).");

        // Step 2: Put them on a BlockingQueue
        BlockingQueue<IncomingTransaction> queue = new LinkedBlockingQueue<>(500);
        for (IncomingTransaction txn : queuedTxns) {
            try {
                queue.put(txn);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("  [ERROR] Interrupted while queuing.");
                return;
            }
        }

        // Step 3: Put shutdown sentinel so dispatcher knows when to stop
        IncomingTransaction sentinel = new IncomingTransaction();
        sentinel.setSourceRef("SHUTDOWN");
        try {
            queue.put(sentinel);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Step 4: Start TransactionDispatcher in a background thread and wait for it
        TransactionDispatcher dispatcher = new TransactionDispatcher(queue);
        Thread dispatcherThread = new Thread(dispatcher, "DispatcherThread-1");
        dispatcherThread.start();
        try {
            dispatcherThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("  [Dispatch] Done. Check DB -> credit/debit/interbank/reversal tables.");
    }

    // -----------------------------------------------------------------------
    // OPTION 3 — Settlement Engine
    //
    // NOTE: We do NOT call SettlementEngineTest.main() because it calls
    //       ConnectionPool.shutdown() in its finally block.
    //       We call SettlementEngine directly instead.
    // -----------------------------------------------------------------------

    private static void runSettlementEngine() {
        System.out.println("\n  [Settlement] Settling INITIATED transactions...\n");
        try {
            SettlementEngine engine = new SettlementEngine();
            engine.runSettlement();
            System.out.println("  [Settlement] Done. Check DB -> settlement_batch, settlement_record tables.");
        } catch (Exception e) {
            System.out.println("  [ERROR] Settlement failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // OPTION 4 — View Incoming Transactions by status
    // -----------------------------------------------------------------------

    private static void viewIncomingTransactions() {
        System.out.println("\n  1=RECEIVED  2=VALIDATED  3=QUEUED  4=PROCESSING");
        System.out.println("  5=PROCESSED 6=FAILED     7=DEAD_LETTER");
        System.out.print("  Choose status (1-7): ");

        ProcessingStatus status;
        switch (scanner.nextLine().trim()) {
            case "1": status = ProcessingStatus.RECEIVED;    break;
            case "2": status = ProcessingStatus.VALIDATED;   break;
            case "3": status = ProcessingStatus.QUEUED;      break;
            case "4": status = ProcessingStatus.PROCESSING;  break;
            case "5": status = ProcessingStatus.PROCESSED;   break;
            case "6": status = ProcessingStatus.FAILED;      break;
            case "7": status = ProcessingStatus.DEAD_LETTER; break;
            default:  System.out.println("  Invalid choice."); return;
        }

        try {
            List<IncomingTransaction> list = incomingDao.findByStatus(status);
            if (list.isEmpty()) {
                System.out.println("  No transactions found with status: " + status);
                return;
            }
            System.out.println();
            // NOTE: IncomingTransaction does NOT have getCurrency().
            //       Currency is stored inside normalizedPayload JSON only.
            //       So we show sourceRef and txnType instead.
            System.out.printf("  %-6s  %-20s  %-12s  %-12s  %-15s%n",
                    "ID", "SOURCE REF", "TYPE", "AMOUNT", "STATUS");
            System.out.println("  " + "-".repeat(72));
            for (IncomingTransaction t : list) {
                System.out.printf("  %-6s  %-20s  %-12s  %-12s  %-15s%n",
                        t.getIncomingTxnId(),
                        t.getSourceRef()    != null ? t.getSourceRef()                     : "-",
                        t.getTxnType()      != null ? t.getTxnType().name()                : "-",
                        t.getAmount()       != null ? t.getAmount().toPlainString()         : "-",
                        t.getProcessingStatus() != null ? t.getProcessingStatus().name()   : "-");
            }
            System.out.println("  Total: " + list.size() + " record(s)");
        } catch (Exception e) {
            System.out.println("  [ERROR] " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // OPTION 5 — View Settlement Batch + its Records
    // -----------------------------------------------------------------------

    private static void viewSettlementBatch() {
        System.out.println("\n  Example IDs: BATCH-CBS-20260407  BATCH-RTGS-20260407");
        System.out.println("               BATCH-NEFT-20260407 BATCH-UPI-20260407  BATCH-FT-20260407");
        System.out.print("  Enter Batch ID: ");
        String batchId = scanner.nextLine().trim();

        if (batchId.isEmpty()) { System.out.println("  Batch ID cannot be empty."); return; }

        try {
            SettlementBatch batch = batchDao.findBatchById(batchId);
            if (batch == null) { System.out.println("  Batch not found: " + batchId); return; }

            System.out.println();
            System.out.println("  Batch ID     : " + batch.getBatchId());
            System.out.println("  Date         : " + batch.getBatchDate());
            System.out.println("  Status       : " + batch.getBatchStatus());
            System.out.println("  Total Txns   : " + batch.getTotalTransactions());
            System.out.println("  Total Amount : " + (batch.getTotalAmount() != null
                                                       ? batch.getTotalAmount().toPlainString() : "-"));
            System.out.println("  Run By       : " + batch.getRunBy());
            System.out.println("  Run At       : " + batch.getRunAt());

            List<SettlementRecord> records = batchDao.findRecordsByBatchId(batchId);
            if (records.isEmpty()) {
                System.out.println("  No settlement records found for this batch.");
                return;
            }
            System.out.println();
            System.out.printf("  %-10s  %-20s  %-14s  %-14s  %-12s%n",
                    "REC ID", "BATCH ID", "INCOMING ID", "AMOUNT", "STATUS");
            System.out.println("  " + "-".repeat(75));
            for (SettlementRecord r : records) {
                System.out.printf("  %-10s  %-20s  %-14s  %-14s  %-12s%n",
                        r.getRecordId(),
                        r.getBatchId(),
                        r.getIncomingTxnId(),
                        r.getSettledAmount() != null ? r.getSettledAmount().toPlainString() : "-",
                        r.getSettledStatus() != null ? r.getSettledStatus().name()          : "-");
            }
            System.out.println("  Total records: " + records.size());
        } catch (Exception e) {
            System.out.println("  [ERROR] " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // OPTION 6 — Check Account by account number
    // -----------------------------------------------------------------------

    private static void checkAccount() {
        System.out.print("\n  Enter Account Number: ");
        String accNo = scanner.nextLine().trim();
        if (accNo.isEmpty()) { System.out.println("  Cannot be empty."); return; }

        try {
            Account acc = accountDao.findByAccountNumber(accNo);
            if (acc == null) { System.out.println("  Account not found: " + accNo); return; }

            System.out.println();
            System.out.println("  Account Number : " + acc.getAccountNumber());
            System.out.println("  Account Type   : " + acc.getAccountType());
            System.out.println("  Balance        : " + (acc.getBalance() != null
                                                         ? acc.getBalance().toPlainString() : "-"));
            System.out.println("  Currency       : " + acc.getCurrency());
            System.out.println("  Status         : " + acc.getStatus());
            System.out.println("  Active?        : " + (accountDao.isAccountActiveByNumber(accNo)
                                                         ? "YES" : "NO"));
        } catch (Exception e) {
            System.out.println("  [ERROR] " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // OPTION 7 — Check Customer KYC by customer ID
    // -----------------------------------------------------------------------

    private static void checkCustomer() {
        System.out.print("\n  Enter Customer ID (number): ");
        String input = scanner.nextLine().trim();
        if (input.isEmpty()) { System.out.println("  Cannot be empty."); return; }

        long customerId;
        try {
            customerId = Long.parseLong(input);
        } catch (NumberFormatException e) {
            System.out.println("  Customer ID must be a number.");
            return;
        }

        try {
            Customer c = customerDao.findById(customerId);
            if (c == null) { System.out.println("  Customer not found: " + customerId); return; }

            System.out.println();
            System.out.println("  Customer ID    : " + c.getId());
            System.out.println("  Name           : " + c.getFirstName() + " " + c.getLastName());
            System.out.println("  Email          : " + c.getEmail());
            System.out.println("  KYC Status     : " + c.getKycStatus());
            System.out.println("  KYC Verified?  : " + (customerDao.isCustomerKycVerified(customerId)
                                                          ? "YES" : "NO"));
            System.out.println("  Tier           : " + c.getCustomerTier());
            System.out.println("  Onboarded On   : " + c.getOnboardingDate());
        } catch (Exception e) {
            System.out.println("  [ERROR] " + e.getMessage());
        }
    }
}