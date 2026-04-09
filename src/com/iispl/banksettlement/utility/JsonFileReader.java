package com.iispl.banksettlement.utility;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * JsonFileReader — Reads a JSON transaction file where each line contains one
 * complete JSON object, and returns each line as a raw payload string.
 *
 * USED FOR: - UPI transactions (payloads for UpiAdapter.adapt()) - Fintech
 * transactions (payloads for FintechAdapter.adapt())
 *
 * FILE FORMAT EXPECTED: - One JSON object per line (JSONL / newline-delimited
 * JSON style) - Each line is a self-contained, complete JSON object starting
 * with '{' - Lines starting with '#' are comments — skipped - Blank lines —
 * skipped
 *
 * EXAMPLE FILE CONTENT:
 * {"upiTxnId":"UPI20260402XYZ9988","payerVpa":"ramesh@okicici",...}
 * {"upiTxnId":"UPI20260402XYZ9989","payerVpa":"priya@okhdfc",...}
 *
 * WHY ONE OBJECT PER LINE? UpiAdapter and FintechAdapter parse JSON using
 * simple String.indexOf() (pure Core Java, no library). This only works on a
 * single-object string. Multi-line pretty-printed JSON with nested indentation
 * would not work with the adapter's field extractor. JSONL format is both
 * simple to write and directly compatible with the existing adapter parsers.
 *
 * REUSED FOR BOTH UPI AND FINTECH: The same reader class works for both because
 * both formats use one-JSON- per-line. The SourceType passed to
 * IngestionPipeline.ingest() determines which adapter processes each payload —
 * UpiAdapter or FintechAdapter. This reader itself is source-agnostic; the
 * label returned by getSourceFormat() can be set at construction time for log
 * clarity.
 */
public class JsonFileReader implements TransactionFileReader {
    private static final Logger LOGGER = Logger.getLogger(JsonFileReader.class.getName());

	// Label used in log output — e.g. "UPI_JSON" or "FINTECH_JSON"
	private final String sourceFormatLabel;

	/**
	 * @param sourceFormatLabel label for this reader instance, e.g. "UPI_JSON" or
	 *                          "FINTECH_JSON"
	 */
	public JsonFileReader(String sourceFormatLabel) {
		this.sourceFormatLabel = sourceFormatLabel;
	}

	@Override
	public List<String> readLines(String filePath) throws IOException {

		List<String> payloads = new ArrayList<>();

		try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {

			String line;
			int lineNumber = 0;

			while ((line = reader.readLine()) != null) {
				lineNumber++;
				String trimmed = line.trim();

				// Skip blank lines
				if (trimmed.isEmpty()) {
					continue;
				}

				// Skip comment lines
				if (trimmed.startsWith("#")) {
					continue;
				}

				// Each valid data line must start with '{' (JSON object)
				if (!trimmed.startsWith("{")) {
                    PhaseLogger.getLogger().warning("[JsonFileReader] Invalid JSON line at " + lineNumber + ". Skipping.");
					continue;
				}

				// Basic JSON completeness check — must also end with '}'
				if (!trimmed.endsWith("}")) {
                    PhaseLogger.getLogger().warning("[JsonFileReader] Truncated JSON at line " + lineNumber + ". Skipping.");
					continue;
				}

				payloads.add(trimmed);
			}
		}

        PhaseLogger.getLogger().info("[JsonFileReader][" + sourceFormatLabel + "] Total records read: " + payloads.size());
		return payloads;
	}

	@Override
	public String getSourceFormat() {
		return sourceFormatLabel;
	}
}
