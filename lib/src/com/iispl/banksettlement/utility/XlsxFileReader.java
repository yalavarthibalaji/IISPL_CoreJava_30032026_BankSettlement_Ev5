package com.iispl.banksettlement.utility;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * XlsxFileReader — Reads a CBS transaction Excel (.xlsx) file using Apache POI
 * and converts each data row into a pipe-delimited string for CbsAdapter.adapt().
 *
 * DEPENDENCY REQUIRED (add to project's lib/ folder or pom.xml):
 *   Apache POI — poi-ooxml-5.x.x.jar (includes poi-5.x.x.jar transitively)
 *   Download: https://poi.apache.org/download.html
 *
 *   Maven dependency:
 *     &lt;dependency&gt;
 *       &lt;groupId&gt;org.apache.poi&lt;/groupId&gt;
 *       &lt;artifactId&gt;poi-ooxml&lt;/artifactId&gt;
 *       &lt;version&gt;5.2.5&lt;/version&gt;
 *     &lt;/dependency&gt;
 *
 * EXCEL FILE FORMAT EXPECTED:
 *   - Sheet name: "CBS_Transactions" (first sheet used if name not found)
 *   - Row 1 (index 0): Header row — skipped automatically
 *   - Rows 2+ : Data rows with 10 columns in this order:
 *
 *     Col A (0): CBS_TXN_ID
 *     Col B (1): ACCT_DR
 *     Col C (2): ACCT_CR
 *     Col D (3): AMT
 *     Col E (4): CCY
 *     Col F (5): TXN_DT      ← stored as text "yyyyMMdd" in Excel
 *     Col G (6): TXN_TYPE
 *     Col H (7): NARRATION
 *     Col I (8): BRANCH_CODE
 *     Col J (9): MAKER_ID
 *
 * WHY DataFormatter?
 *   Apache POI reads numeric cells as Double by default. A date cell stored as
 *   20260402 (plain number) would come back as "2.026042E7" without formatting.
 *   DataFormatter forces every cell to render as its displayed string — the same
 *   value you see in Excel — regardless of the underlying cell type.
 *   This is the safest approach for financial data with mixed cell types.
 *
 * OUTPUT:
 *   Each row is returned as a pipe-delimited string matching CBS wire format:
 *   CBS_TXN_ID|ACCT_DR|ACCT_CR|AMT|CCY|TXN_DT|TXN_TYPE|NARRATION|BRANCH_CODE|MAKER_ID
 *   This is passed directly to CbsAdapter.adapt().
 *
 * NOTE ON TXN_DT COLUMN:
 *   Store date values in Excel as plain text "20260402" (format cells as Text
 *   before entering, or prefix with apostrophe: '20260402). If stored as a real
 *   Excel date serial, DataFormatter will render it in the sheet's locale date
 *   format which may not match yyyyMMdd. Using Text format in Excel is safest.
 */
public class XlsxFileReader implements TransactionFileReader {

    private static final String EXPECTED_SHEET_NAME = "CBS_Transactions";
    private static final int    EXPECTED_COLUMNS     = 10;
    private static final int    HEADER_ROW_INDEX     = 0; // Row index 0 = Row 1 in Excel

    // DataFormatter renders cell values exactly as displayed in Excel
    private final DataFormatter dataFormatter = new DataFormatter();

    @Override
    public List<String> readLines(String filePath) throws IOException {

        List<String> payloads = new ArrayList<>();

        try (FileInputStream fis      = new FileInputStream(filePath);
             Workbook        workbook = new XSSFWorkbook(fis)) {

            // Use named sheet if found, else fall back to first sheet
            Sheet sheet = workbook.getSheet(EXPECTED_SHEET_NAME);
            if (sheet == null) {
                System.out.println("[XlsxFileReader] Sheet '" + EXPECTED_SHEET_NAME
                        + "' not found — using first sheet: '"
                        + workbook.getSheetAt(0).getSheetName() + "'");
                sheet = workbook.getSheetAt(0);
            }

            System.out.println("[XlsxFileReader] Reading sheet: '" + sheet.getSheetName()
                    + "' | Total rows (including header): " + (sheet.getLastRowNum() + 1));

            for (Row row : sheet) {

                int rowIndex = row.getRowNum();

                // Skip header row (row index 0 = Excel row 1)
                if (rowIndex == HEADER_ROW_INDEX) {
                    System.out.println("[XlsxFileReader] Skipping header row (Excel row 1)");
                    continue;
                }

                // Skip completely blank rows
                if (isRowBlank(row)) {
                    System.out.println("[XlsxFileReader] Skipping blank row at Excel row "
                            + (rowIndex + 1));
                    continue;
                }

                // Read all 10 columns using DataFormatter
                String[] values = new String[EXPECTED_COLUMNS];
                for (int col = 0; col < EXPECTED_COLUMNS; col++) {
                    Cell cell = row.getCell(col);
                    if (cell == null) {
                        values[col] = "";
                    } else {
                        values[col] = dataFormatter.formatCellValue(cell).trim();
                    }
                }

                // Validate that at minimum CBS_TXN_ID and AMT are present
                if (values[0].isEmpty() || values[3].isEmpty()) {
                    System.out.println("[XlsxFileReader] WARNING — Excel row " + (rowIndex + 1)
                            + " missing CBS_TXN_ID or AMT. Skipping.");
                    continue;
                }

                // Build pipe-delimited payload string for CbsAdapter
                String pipePayload = values[0]        // CBS_TXN_ID
                        + "|" + values[1]              // ACCT_DR
                        + "|" + values[2]              // ACCT_CR
                        + "|" + values[3]              // AMT
                        + "|" + values[4]              // CCY
                        + "|" + values[5]              // TXN_DT  (yyyyMMdd as text)
                        + "|" + values[6]              // TXN_TYPE
                        + "|" + values[7]              // NARRATION
                        + "|" + values[8]              // BRANCH_CODE
                        + "|" + values[9];             // MAKER_ID

                payloads.add(pipePayload);
                System.out.println("[XlsxFileReader] Read CBS record at Excel row " + (rowIndex + 1)
                        + " | ref: " + values[0]);
            }
        }

        System.out.println("[XlsxFileReader] Total CBS records read from Excel: "
                + payloads.size() + " from file: " + filePath);
        return payloads;
    }

    @Override
    public String getSourceFormat() {
        return "CBS_XLSX";
    }

    /**
     * Checks whether all cells in a row are blank or null.
     * Used to skip completely empty rows that Excel sometimes adds at the end.
     */
    private boolean isRowBlank(Row row) {
        for (int col = 0; col < EXPECTED_COLUMNS; col++) {
            Cell cell = row.getCell(col);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String val = dataFormatter.formatCellValue(cell).trim();
                if (!val.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }
}
