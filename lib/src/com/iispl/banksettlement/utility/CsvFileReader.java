package com.iispl.banksettlement.utility;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * CsvFileReader — Reads a CBS CSV transaction file and converts each data row
 * into a pipe-delimited string that CbsAdapter.adapt() understands.
 *
 * FILE FORMAT EXPECTED:
 *   - Comma-separated values (CSV)
 *   - First non-comment line is the header row (skipped automatically)
 *   - Lines starting with '#' are comments — skipped
 *   - Blank lines are skipped
 *   - Each data row has 10 columns matching CBS pipe format:
 *       CBS_TXN_ID, ACCT_DR, ACCT_CR, AMT, CCY, TXN_DT, TXN_TYPE,
 *       NARRATION, BRANCH_CODE, MAKER_ID
 *
 * WHY CONVERSION TO PIPE-DELIMITED?
 *   CbsAdapter.adapt() was designed for the CBS wire format which uses '|' as
 *   delimiter. Rather than changing the adapter, this reader converts the CSV row
 *   to pipe-delimited so the existing adapter works unchanged. This is consistent
 *   with the adapter's contract — it always gets the same format regardless of
 *   whether the data came from a hardcoded string or a file.
 *
 * EXAMPLE INPUT LINE:
 *   C001,ACC-HDFC-001,ACC-HDFC-002,25000.00,INR,20260402,CREDIT,Salary credit,BLR001,EMP4521
 *
 * EXAMPLE OUTPUT STRING (returned in list):
 *   C001|ACC-HDFC-001|ACC-HDFC-002|25000.00|INR|20260402|CREDIT|Salary credit|BLR001|EMP4521
 */
public class CsvFileReader implements TransactionFileReader {

    // The header columns expected in the CBS CSV file (case-insensitive check)
    private static final String HEADER_INDICATOR = "CBS_TXN_ID";

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

                // Skip comment lines (start with #)
                if (trimmed.startsWith("#")) {
                    System.out.println("[CsvFileReader] Skipping comment at line " + lineNumber);
                    continue;
                }

                // Skip the header row — identified by the first column name
                if (trimmed.toUpperCase().startsWith(HEADER_INDICATOR)) {
                    System.out.println("[CsvFileReader] Skipping header at line " + lineNumber);
                    continue;
                }

                // Split on comma — CBS CSV has exactly 10 fields
                String[] parts = trimmed.split(",", -1);

                if (parts.length < 10) {
                    System.out.println("[CsvFileReader] WARNING — line " + lineNumber
                            + " has only " + parts.length + " columns (expected 10). Skipping: "
                            + trimmed);
                    continue;
                }

                // Convert CSV row → pipe-delimited string for CbsAdapter
                // Trim each part to remove accidental whitespace from the CSV
                String pipePayload = parts[0].trim()  // CBS_TXN_ID
                        + "|" + parts[1].trim()         // ACCT_DR
                        + "|" + parts[2].trim()         // ACCT_CR
                        + "|" + parts[3].trim()         // AMT
                        + "|" + parts[4].trim()         // CCY
                        + "|" + parts[5].trim()         // TXN_DT
                        + "|" + parts[6].trim()         // TXN_TYPE
                        + "|" + parts[7].trim()         // NARRATION
                        + "|" + parts[8].trim()         // BRANCH_CODE
                        + "|" + parts[9].trim();        // MAKER_ID

                payloads.add(pipePayload);
                System.out.println("[CsvFileReader] Read CBS record at line " + lineNumber
                        + " | ref: " + parts[0].trim());
            }
        }

        System.out.println("[CsvFileReader] Total CBS records read: " + payloads.size()
                + " from file: " + filePath);
        return payloads;
    }

    @Override
    public String getSourceFormat() {
        return "CBS_CSV";
    }
}
