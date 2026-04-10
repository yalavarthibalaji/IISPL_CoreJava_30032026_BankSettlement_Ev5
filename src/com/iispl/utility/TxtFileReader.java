package com.iispl.utility;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * TxtFileReader — Reads a NEFT fixed-width text batch file and returns each
 * 132-character data record as a raw payload string for NeftAdapter.adapt().
 *
 * FILE FORMAT EXPECTED: - Fixed-width text file, one record per line - Each
 * data record is exactly 132 characters wide - Lines starting with '#' are
 * comments — skipped - Blank lines — skipped - Records starting with CR or DR
 * are data lines - Field positions (0-based): [0-1] RECORD_TYPE (2 chars) — CR
 * or DR [2-17] NEFT_REF (16 chars) [18-28] SENDER_IFSC (11 chars) [29-48]
 * SENDER_ACCT (20 chars) [49-59] BENE_IFSC (11 chars) [60-79] BENE_ACCT (20
 * chars) [80-109] BENE_NAME (30 chars) [110-121]AMT (12 chars)
 * [122-125]PURPOSE_CODE ( 4 chars) [126-131]BATCH_NO ( 6 chars)
 *
 * Each line is passed as-is to NeftAdapter — no conversion needed since
 * NeftAdapter reads by fixed character positions, not by delimiter.
 *
 * LENGTH VALIDATION: Lines shorter than 132 chars are logged as warnings and
 * skipped. This mirrors the validation NeftAdapter already does inside adapt().
 */
public class TxtFileReader implements TransactionFileReader {
    private static final Logger LOGGER = Logger.getLogger(TxtFileReader.class.getName());

	private static final int NEFT_RECORD_LENGTH = 172;

	@Override
	public List<String> readLines(String filePath) throws IOException {

		List<String> payloads = new ArrayList<>();

		try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {

			String line;
			int lineNumber = 0;

			while ((line = reader.readLine()) != null) {
				lineNumber++;

				// Skip blank lines
				if (line.trim().isEmpty()) {
					continue;
				}

				// Skip comment lines
				if (line.trim().startsWith("#")) {
					continue;
				}

				// Length guard — NEFT records must be exactly 132 characters
				if (line.length() < NEFT_RECORD_LENGTH) {
                    PhaseLogger.getLogger().warning("[TxtFileReader] Invalid NEFT line length at " + lineNumber + ". Skipping.");
					continue;
				}

				// Verify record type starts with CR or DR
				String recordType = line.substring(0, 2).trim();
				if (!recordType.equalsIgnoreCase("CR") && !recordType.equalsIgnoreCase("DR")) {
                    PhaseLogger.getLogger().warning("[TxtFileReader] Invalid NEFT record type at line " + lineNumber + ". Skipping.");
					continue;
				}

				payloads.add(line);
			}
		}

        PhaseLogger.getLogger().info("[TxtFileReader] Total NEFT records read: " + payloads.size());
		return payloads;
	}

	@Override
	public String getSourceFormat() {
		return "NEFT_TXT";
	}
}
