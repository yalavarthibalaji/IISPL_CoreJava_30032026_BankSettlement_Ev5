package com.iispl;

import com.iispl.connectionpool.ConnectionPool;
import com.iispl.dao.IncomingTransactionDao;
import com.iispl.dao.impl.IncomingTransactionDaoImpl;
import com.iispl.entity.IncomingTransaction;
import com.iispl.entity.NettingPosition;
import com.iispl.entity.ReconciliationEntry;
import com.iispl.enums.ProcessingStatus;
import com.iispl.enums.SourceType;
import com.iispl.exception.AccountNotFoundException;
import com.iispl.exception.TransactionNotFoundException;
import com.iispl.service.impl.NettingServiceImpl;
import com.iispl.service.impl.ReconciliationServiceImpl;
import com.iispl.service.impl.SettlementEngineImpl;
import com.iispl.threading.IngestionPipeline;
import com.iispl.threading.TransactionDispatcher;
import com.iispl.utility.ConsoleDisplayUtil;
import com.iispl.utility.CsvFileReader;
import com.iispl.utility.JsonFileReader;
import com.iispl.utility.PhaseLogger;
import com.iispl.utility.SummaryReportExporter;
import com.iispl.utility.TxtFileReader;
import com.iispl.utility.XmlFileReader;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BankSettlementApp {

	private static final String FILE_BASE_PATH = "src/com/iispl/testfiles/";

	private static final Logger APP_LOGGER = Logger.getLogger(BankSettlementApp.class.getName());

	private static final DateTimeFormatter BANNER_DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy  HH:mm:ss");

	// -----------------------------------------------------------------------
	// MAIN METHOD
	// -----------------------------------------------------------------------

	public static void main(String[] args) {

		System.setProperty("org.slf4j.simpleLogger.log.com.zaxxer.hikari", "warn");
		PhaseLogger.configureReadableConsole(APP_LOGGER);
		Logger.getLogger("com.zaxxer.hikari").setLevel(Level.WARNING);

		Scanner scanner = new Scanner(System.in);
		printWelcomeBanner();

		int choice = -1;

		do {
			printMainMenu();
			System.out.print("  Enter your choice : ");
			String input = scanner.nextLine().trim();

			if (!input.matches("[0-9]+")) {
				printError("Please enter a number between 0 and 3.");
				continue;
			}

			choice = Integer.parseInt(input);

			switch (choice) {
			case 1:
				runPipelineMenu(scanner);
				break;
			case 2:
				runViewTablesMenu(scanner);
				break;
			case 3:
				SummaryReportExporter.exportReport();
				break;
			case 0:
				printGoodbye();
				ConnectionPool.shutdown();
				break;
			default:
				printError("Invalid choice. Please enter 0, 1, 2, or 3.");
			}

		} while (choice != 0);

		scanner.close();
	}

	// -----------------------------------------------------------------------
	// PIPELINE SUB-MENU
	// -----------------------------------------------------------------------

	private static void runPipelineMenu(Scanner scanner) {
		int choice = -1;
		do {
			printPipelineMenu();
			System.out.print("  Enter your choice : ");
			String input = scanner.nextLine().trim();

			if (!input.matches("[0-9]+")) {
				printError("Please enter a valid number.");
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
				printStepInfo("Running FULL PIPELINE (Steps 1 to 5)...");
				runIngestion();
				runDispatch();
				runSettlement();
				runNetting();
				runReconciliation();
				printStepInfo("Full pipeline completed.");
				break;
			case 0:
				break; // back to main menu
			default:
				printError("Please enter a number between 0 and 6.");
			}
		} while (choice != 0);
	}

	// -----------------------------------------------------------------------
	// VIEW TABLES SUB-MENU
	// -----------------------------------------------------------------------

	/**
	 * View Tables sub-menu.
	 *
	 * Stream filter options (2, 3, 12, 14) ask the user to type a filter value,
	 * then call the corresponding ConsoleDisplayUtil method which uses Streams
	 * internally to filter data. If the filter produces no result, a custom
	 * exception is caught here and shown as a friendly error message.
	 */
	private static void runViewTablesMenu(Scanner scanner) {
		int choice = -1;
		do {
			printViewTablesMenu();
			System.out.print("  Enter your choice : ");
			String input = scanner.nextLine().trim();

			if (!input.matches("[0-9]+")) {
				printError("Please enter a valid number.");
				continue;
			}

			choice = Integer.parseInt(input);

			switch (choice) {

			// --- Plain table views (no filter) ---
			case 1:
				ConsoleDisplayUtil.showIncomingTransactions();
				break;
			case 4:
				ConsoleDisplayUtil.showSettlementBatches();
				break;
			case 5:
				ConsoleDisplayUtil.showSettlementRecords();
				break;
			case 6:
				ConsoleDisplayUtil.showCreditTransactions();
				break;
			case 7:
				ConsoleDisplayUtil.showDebitTransactions();
				break;
			case 8:
				ConsoleDisplayUtil.showInterBankTransactions();
				break;
			case 9:
				ConsoleDisplayUtil.showReversalTransactions();
				break;
			case 10:
				ConsoleDisplayUtil.showNettingPositions();
				break;
			case 11:
				ConsoleDisplayUtil.showReconciliationEntries();
				break;
			case 13:
				ConsoleDisplayUtil.showNpciMemberAccounts();
				break;

			// --- Stream filter: Incoming by Source ---
			case 2: {
				System.out.print("  Enter source to filter (CBS / RTGS / NEFT / UPI / FINTECH) : ");
				String source = scanner.nextLine().trim();
				try {
					// This internally uses Stream.filter() — see ConsoleDisplayUtil
					ConsoleDisplayUtil.showIncomingBySource(source);
				} catch (TransactionNotFoundException ex) {
					// Custom exception caught — show friendly message
					printError("Not Found: " + ex.getMessage());
					printError("  Searched for source : " + ex.getSearchTerm());
				}
				break;
			}

			// --- Stream filter: Incoming by Status ---
			case 3: {
				System.out.print("  Enter status to filter (PROCESSED / QUEUED / DUPLICATE / FAILED) : ");
				String status = scanner.nextLine().trim();
				try {
					// This internally uses Stream.filter() — see ConsoleDisplayUtil
					ConsoleDisplayUtil.showIncomingByStatus(status);
				} catch (TransactionNotFoundException ex) {
					printError("Not Found: " + ex.getMessage());
					printError("  Searched for status : " + ex.getSearchTerm());
				}
				break;
			}

			// --- Stream filter: Reconciliation by Status ---
			case 12: {
				System.out.print("  Enter reconciliation status (MATCHED / UNMATCHED) : ");
				String status = scanner.nextLine().trim();
				try {
					// This internally uses Stream.filter() — see ConsoleDisplayUtil
					ConsoleDisplayUtil.showReconByStatus(status);
				} catch (AccountNotFoundException ex) {
					printError("Not Found: " + ex.getMessage());
					printError("  Searched for status : " + ex.getSearchTerm());
				}
				break;
			}

			// --- Stream filter: NPCI Account by Bank Name ---
			case 14: {
				System.out.print("  Enter bank name to search (e.g. HDFC / SBI / ICICI) : ");
				String bankName = scanner.nextLine().trim();
				try {
					// This internally uses Stream.filter() — see ConsoleDisplayUtil
					ConsoleDisplayUtil.showNpciAccountByBank(bankName);
				} catch (AccountNotFoundException ex) {
					printError("Not Found: " + ex.getMessage());
					printError("  Searched for bank  : " + ex.getSearchTerm());
				}
				break;
			}

			case 0:
				break; // back to main menu
			default:
				printError("Please enter a number between 0 and 14.");
			}
		} while (choice != 0);
	}

	// -----------------------------------------------------------------------
	// MENU DISPLAY METHODS
	// -----------------------------------------------------------------------

	// -----------------------------------------------------------------------
	// MENU DISPLAY METHODS (SIMPLE VERSION)
	// -----------------------------------------------------------------------

	private static void printWelcomeBanner() {
		String now = LocalDateTime.now().format(BANNER_DATE_FMT);

		System.out.println();
		System.out.println("BANK TRANSACTION SETTLEMENT SYSTEM");
		System.out.println("IISPL - Core Java Training Project");
		System.out.println("Version : E5");
		System.out.println("Started : " + now);
		System.out.println();
	}

	private static void printMainMenu() {
		System.out.println();
		System.out.println("MAIN MENU");
		System.out.println("1. Run Pipeline Steps");
		System.out.println("2. View Database Tables");
		System.out.println("3. Export Summary Report (Excel)");
		System.out.println("0. Exit");
	}

	private static void printPipelineMenu() {
		System.out.println();
		System.out.println("RUN PIPELINE STEPS");
		System.out.println("1. File Ingestion");
		System.out.println("2. Transaction Dispatch");
		System.out.println("3. Settlement Engine");
		System.out.println("4. Netting Engine");
		System.out.println("5. Reconciliation");
		System.out.println("6. Run Full Pipeline");
		System.out.println("0. Back");
	}

	private static void printViewTablesMenu() {
		System.out.println();
		System.out.println("VIEW DATABASE TABLES");

		System.out.println("\nINGESTION");
		System.out.println("1. Incoming Transactions");
		System.out.println("2. Incoming by Source");
		System.out.println("3. Incoming by Status");

		System.out.println("\nSETTLEMENT");
		System.out.println("4. Settlement Batches");
		System.out.println("5. Settlement Records");

		System.out.println("\nDISPATCHED TRANSACTIONS");
		System.out.println("6. Credit Transactions");
		System.out.println("7. Debit Transactions");
		System.out.println("8. InterBank Transactions");
		System.out.println("9. Reversal Transactions");

		System.out.println("\nNETTING & RECONCILIATION");
		System.out.println("10. Netting Positions");
		System.out.println("11. Reconciliation Entries");
		System.out.println("12. Reconciliation by Status");
		System.out.println("13. NPCI Bank Accounts");
		System.out.println("14. NPCI Account by Bank");

		System.out.println("\n0. Back");
	}

	private static void printError(String message) {
		System.out.println();
		System.out.println("  [!] " + message);
	}

	private static void printStepInfo(String message) {
		System.out.println();
		System.out.println("  [INFO] " + message);
	}

	private static void printGoodbye() {
		System.out.println();
		System.out.println("  ┌──────────────────────────────────────────┐");
		System.out.println("  │   Thank you. Goodbye!                    │");
		System.out.println("  └──────────────────────────────────────────┘");
		System.out.println();
	}

	// -----------------------------------------------------------------------
	// PIPELINE STEP METHODS
	// -----------------------------------------------------------------------

	private static void runIngestion() {
		try (PhaseLogger.PhaseLogContext ignored = PhaseLogger.startPhase("phase1_ingestion",
				"STEP 1 - FILE INGESTION")) {

			Logger logger = PhaseLogger.getLogger();
			IngestionPipeline pipeline = new IngestionPipeline();

			ingestFile(pipeline, SourceType.CBS, FILE_BASE_PATH + "cbs_transactions.csv", new CsvFileReader());
			ingestFile(pipeline, SourceType.RTGS, FILE_BASE_PATH + "rtgs_transactions.xml", new XmlFileReader());
			ingestFile(pipeline, SourceType.NEFT, FILE_BASE_PATH + "neft_transactions.txt", new TxtFileReader());
			ingestFile(pipeline, SourceType.UPI, FILE_BASE_PATH + "upi_transactions.json",
					new JsonFileReader("UPI_JSON"));
			ingestFile(pipeline, SourceType.FINTECH, FILE_BASE_PATH + "fintech_transactions.json",
					new JsonFileReader("FINTECH_JSON"));

			logger.info("All files submitted. Waiting for workers to finish.");
			pipeline.shutdownExecutorOnly();
			logger.info("Ingestion completed successfully.");

		} catch (Exception e) {
			PhaseLogger.getLogger().log(Level.SEVERE, "Ingestion failed: " + e.getMessage(), e);
		}
	}

	private static void ingestFile(IngestionPipeline pipeline, SourceType sourceType, String filePath,
			com.iispl.utility.TransactionFileReader reader) {
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

	private static void runDispatch() {
		try (PhaseLogger.PhaseLogContext ignored = PhaseLogger.startPhase("phase2_dispatch",
				"STEP 2 - TRANSACTION DISPATCH")) {

			Logger logger = PhaseLogger.getLogger();
			int queuedCount = countByStatus("incoming_transaction", "processing_status", "QUEUED");
			logger.info("Incoming QUEUED records before dispatch: " + queuedCount);

			IncomingTransactionDao dao = new IncomingTransactionDaoImpl();
			List<IncomingTransaction> txns = dao.findByStatus(ProcessingStatus.QUEUED);

			if (txns.isEmpty()) {
				logger.info("No QUEUED transactions found. Run ingestion first.");
				return;
			}

			logger.info("Found " + txns.size() + " QUEUED transactions.");
			BlockingQueue<IncomingTransaction> queue = new LinkedBlockingQueue<>(500);
			for (IncomingTransaction txn : txns) {
				try {
					queue.put(txn);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}

			IncomingTransaction sentinel = new IncomingTransaction();
			sentinel.setSourceRef("SHUTDOWN");
			try {
				queue.put(sentinel);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			TransactionDispatcher dispatcher = new TransactionDispatcher(queue);
			Thread t = new Thread(dispatcher, "Dispatcher-Thread");
			t.start();
			try {
				t.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			logger.info("Dispatch output → CREDIT: " + countByStatus("credit_transaction", "status", "INITIATED")
					+ " | DEBIT: " + countByStatus("debit_transaction", "status", "INITIATED") + " | INTERBANK: "
					+ countByStatus("interbank_transaction", "status", "INITIATED") + " | REVERSAL: "
					+ countByStatus("reversal_transaction", "status", "INITIATED"));
			logger.info("Dispatch completed successfully.");

		} catch (Exception e) {
			PhaseLogger.getLogger().log(Level.SEVERE, "Dispatch failed: " + e.getMessage(), e);
		}
	}

	private static void runSettlement() {
		try (PhaseLogger.PhaseLogContext ignored = PhaseLogger.startPhase("phase3_settlement",
				"STEP 3 - SETTLEMENT ENGINE")) {
			new SettlementEngineImpl().runSettlement();
			PhaseLogger.getLogger().info("Settlement completed successfully.");
		} catch (Exception e) {
			PhaseLogger.getLogger().log(Level.SEVERE, "Settlement failed: " + e.getMessage(), e);
		}
	}

	private static void runNetting() {
		try (PhaseLogger.PhaseLogContext ignored = PhaseLogger.startPhase("phase4_netting",
				"STEP 4 - NETTING ENGINE")) {
			List<NettingPosition> positions = new NettingServiceImpl().runNetting();
			PhaseLogger.getLogger().info("Netting completed. Positions: " + positions.size());
		} catch (Exception e) {
			PhaseLogger.getLogger().log(Level.SEVERE, "Netting failed: " + e.getMessage(), e);
		}
	}

	private static void runReconciliation() {
		try (PhaseLogger.PhaseLogContext ignored = PhaseLogger.startPhase("phase5_reconciliation",
				"STEP 5 - RECONCILIATION")) {
			List<ReconciliationEntry> entries = new ReconciliationServiceImpl().runReconciliation();
			PhaseLogger.getLogger().info("Reconciliation completed. Entries: " + entries.size());
		} catch (Exception e) {
			PhaseLogger.getLogger().log(Level.SEVERE, "Reconciliation failed: " + e.getMessage(), e);
		}
	}

	private static int countByStatus(String tableName, String statusColumn, String status) {
		String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE " + statusColumn + " = ?";
		try (Connection conn = ConnectionPool.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, status);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next())
					return rs.getInt(1);
			}
		} catch (Exception e) {
			APP_LOGGER.warning("Could not count: " + tableName + " | " + e.getMessage());
		}
		return 0;
	}
}