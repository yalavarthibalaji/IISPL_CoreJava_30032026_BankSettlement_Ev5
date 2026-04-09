package com.iispl.banksettlement;

import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.threading.IngestionPipeline;
import com.iispl.banksettlement.utility.CsvFileReader;
import com.iispl.banksettlement.utility.JsonFileReader;
import com.iispl.banksettlement.utility.TxtFileReader;
import com.iispl.banksettlement.utility.XlsxFileReader;
import com.iispl.banksettlement.utility.XmlFileReader;

import java.io.IOException;
import java.util.List;

/**
 * FileIngestionTest — Tests the complete ingestion pipeline by reading real
 * transaction files in different formats and pushing them through the
 * IngestionPipeline exactly as IngestionPhaseTest does, but via files.
 *
 * FILES USED (all in src/com/iispl/banksettlement/testfiles/):
 * cbs_transactions.csv → CSV → CsvFileReader → CbsAdapter (pipe-delimited)
 * rtgs_transactions.xml → XML → XmlFileReader → RtgsAdapter (XML blocks)
 * neft_transactions.txt → TXT → TxtFileReader → NeftAdapter (fixed-width 132
 * chars) upi_transactions.json → JSON → JsonFileReader → UpiAdapter (one JSON
 * per line) fintech_transactions.json → JSON → JsonFileReader →
 * FintechAdapter(one JSON per line) cbs_transactions.xlsx → XLSX →
 * XlsxFileReader → CbsAdapter (Excel rows → pipe)
 *
 * FLOW PER FILE: FileReader.readLines(path) → List<String> (raw payload per
 * transaction) → IngestionPipeline.ingest(SourceType, rawPayload) (one call per
 * payload) → IngestionWorker (adapt → deduplicate → save DB → queue)
 *
 * HOW TO RUN: 1. Ensure Supabase (or local DB) is active and db.properties is
 * configured. 2. For XLSX: ensure Apache POI jars are on the classpath. 3.
 * Update FILE_BASE_PATH below to the absolute path of your testfiles/ folder.
 * 4. Run FileIngestionTest.main().
 *
 * NOTE ON FILE_BASE_PATH: Eclipse users: right-click the project → Properties →
 * Java Build Path → Source → check where your source root is. Typically the
 * testfiles/ folder will be at:
 * <workspace>/<project>/src/com/iispl/banksettlement/testfiles/
 */
public class FileIngestionTest {

	// -----------------------------------------------------------------------
	// CONFIGURE THIS PATH to point to your local testfiles/ directory.
	// Use forward slashes even on Windows.
	// Example Windows:
	// "C:/workspace/BankSettlement/src/com/iispl/banksettlement/testfiles/"
	// Example Mac/Linux:
	// "/Users/yourname/workspace/BankSettlement/src/com/iispl/banksettlement/testfiles/"
	// -----------------------------------------------------------------------
	private static final String FILE_BASE_PATH = "src/com/iispl/banksettlement/testfiles/";

	public static void main(String[] args) {

		System.out.println("================================================");
		System.out.println("  BANK SETTLEMENT — FILE INGESTION TEST");
		System.out.println("================================================\n");

		IngestionPipeline pipeline = new IngestionPipeline();

		// 1. CBS — CSV file → CsvFileReader → pipe-delimited → CbsAdapter

		ingestFromFile(pipeline, SourceType.CBS, FILE_BASE_PATH + "cbs_transactions.csv", new CsvFileReader());

		// 2. RTGS — XML file → XmlFileReader → RTGSMessage blocks → RtgsAdapter

		ingestFromFile(pipeline, SourceType.RTGS, FILE_BASE_PATH + "rtgs_transactions.xml", new XmlFileReader());

		// 3. NEFT — TXT fixed-width file → TxtFileReader → 132-char records →

		ingestFromFile(pipeline, SourceType.NEFT, FILE_BASE_PATH + "neft_transactions.txt", new TxtFileReader());

		// 4. UPI — JSON file → JsonFileReader → one JSON per line → UpiAdapter

		ingestFromFile(pipeline, SourceType.UPI, FILE_BASE_PATH + "upi_transactions.json",
				new JsonFileReader("UPI_JSON"));

		// 5. Fintech — JSON file → JsonFileReader → one JSON per line → FintechAdapter

		ingestFromFile(pipeline, SourceType.FINTECH, FILE_BASE_PATH + "fintech_transactions.json",
				new JsonFileReader("FINTECH_JSON"));

		// 6. CBS — XLSX file → XlsxFileReader → pipe-delimited rows → CbsAdapter

		ingestFromFile(pipeline, SourceType.CBS, FILE_BASE_PATH + "cbs_transactions.xlsx", new XlsxFileReader());

		System.out.println("\n[FileIngestionTest] All file reads submitted to pipeline.");
		System.out.println("[FileIngestionTest] Waiting for workers to finish...");

		pipeline.shutdown();

		System.out.println("  FILE INGESTION TEST COMPLETE");
		
	}

	// Helper — reads all payloads from a file and submits each to pipeline

	private static void ingestFromFile(IngestionPipeline pipeline, SourceType sourceType, String filePath,
			com.iispl.banksettlement.utility.TransactionFileReader reader) {

		System.out.println("\n--- Reading [" + reader.getSourceFormat() + "] from: " + filePath + " ---");

		List<String> payloads;

		try {
			payloads = reader.readLines(filePath);
		} catch (IOException e) {
			System.out.println("[FileIngestionTest] ERROR reading file [" + filePath + "]: " + e.getMessage());
			return;
		}

		if (payloads.isEmpty()) {
			System.out.println("[FileIngestionTest] WARNING — no records found in file: " + filePath);
			return;
		}

		System.out.println("[FileIngestionTest] Submitting " + payloads.size() + " record(s) from ["
				+ reader.getSourceFormat() + "] to pipeline...");

		for (String rawPayload : payloads) {
			pipeline.ingest(sourceType, rawPayload);
		}
	}
}
