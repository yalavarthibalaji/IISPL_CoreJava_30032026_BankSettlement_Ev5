package com.iispl.banksettlement.utility;

import java.io.IOException;
import java.util.List;

/**
 * TransactionFileReader — Interface that ALL file reader utilities must implement.
 *
 * DESIGN PATTERN: Strategy Pattern (same as TransactionAdapter).
 *   Each file format (CSV, XML, TXT, JSON, XLSX) has its own reader class.
 *   The FileIngestionTest picks the right reader per file and calls readLines()
 *   to get raw payload strings, then passes each string to IngestionPipeline.ingest().
 *
 * CONTRACT:
 *   readLines(filePath) — reads the file and returns a List of raw payload strings.
 *   Each string in the list is exactly one raw payload ready for an adapter's adapt().
 *
 *   getSourceFormat() — returns a human-readable label for logging
 *   (e.g. "CBS_CSV", "RTGS_XML", "NEFT_TXT", "UPI_JSON", "FINTECH_JSON", "XLSX").
 *
 * IMPORTANT — what "one payload string" means per format:
 *   CSV   → one comma-separated line (header and comment lines excluded).
 *           The reader converts CSV commas → pipe-delimited for CbsAdapter.
 *   XML   → one complete <RTGSMessage>...</RTGSMessage> block extracted
 *           from the batch file. RtgsAdapter receives it as a standalone XML string.
 *   TXT   → one 132-char fixed-width line (comment lines excluded).
 *           Passed as-is to NeftAdapter.
 *   JSON  → one complete JSON object per line (one transaction per line).
 *           Passed as-is to UpiAdapter or FintechAdapter.
 *   XLSX  → one pipe-delimited string built from one data row.
 *           The reader converts the Excel row cells → pipe-delimited for CbsAdapter.
 */
public interface TransactionFileReader {

    /**
     * Reads the file at the given path and returns one raw payload string per transaction.
     *
     * @param filePath absolute or classpath-relative path to the transaction file
     * @return         list of raw payload strings, one per transaction
     * @throws IOException if the file cannot be read
     */
    List<String> readLines(String filePath) throws IOException;

    /**
     * Returns a label identifying this reader's format, used for log output.
     * Examples: "CBS_CSV", "RTGS_XML", "NEFT_TXT", "UPI_JSON", "FINTECH_JSON", "XLSX"
     */
    String getSourceFormat();
}
