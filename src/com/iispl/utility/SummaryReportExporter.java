package com.iispl.utility;

import com.iispl.connectionpool.ConnectionPool;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * SummaryReportExporter — Generates a professional Excel (.xls) summary
 * report for the Bank Settlement System.
 *
 * LIBRARY USED:
 *   Apache POI — HSSFWorkbook (.xls format).
 *   WHY HSSFWorkbook and NOT XSSFWorkbook (.xlsx)?
 *     XSSFWorkbook needs commons-compress 1.26+ but our project only has 1.24.
 *     HSSFWorkbook is in poi-5.2.5.jar which is already in your lib/ folder
 *     and needs NO extra dependency. So we use .xls format instead.
 *
 * OUTPUT:
 *   File: reports/BankSettlement_Summary_YYYYMMDD_HHmmss.xls
 *   (reports/ folder is auto-created if it does not exist)
 *
 * SHEETS CREATED:
 *   Sheet 1 — Dashboard Summary   (counts + totals from all tables)
 *   Sheet 2 — Netting Positions   (bilateral from-bank → to-bank net amounts)
 *   Sheet 3 — Reconciliation      (expected vs actual, UNMATCHED highlighted)
 *   Sheet 4 — NPCI Bank Balances  (opening balance vs current balance)
 *
 * HOW TO USE:
 *   SummaryReportExporter.exportReport();
 */
public class SummaryReportExporter {

    // Folder where the report will be saved
    private static final String REPORTS_FOLDER = "reports";

    // Timestamp format used in the file name
    private static final DateTimeFormatter FILE_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // Readable timestamp format used inside the report
    private static final DateTimeFormatter DISPLAY_DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    // -----------------------------------------------------------------------
    // MAIN EXPORT METHOD — call this from the menu
    // -----------------------------------------------------------------------

    /**
     * Generates the full Excel summary report and saves it to the reports/ folder.
     *
     * Call this when user selects "Export Summary Report" from the main menu.
     */
    public static void exportReport() {

        // Create reports/ folder if it does not exist yet
        java.io.File reportsDir = new java.io.File(REPORTS_FOLDER);
        if (!reportsDir.exists()) {
            reportsDir.mkdirs();
        }

        // Build file name with timestamp — e.g. BankSettlement_Summary_20260410_103045.xls
        String timestamp = LocalDateTime.now().format(FILE_DATE_FMT);
        String fileName  = REPORTS_FOLDER + "/BankSettlement_Summary_" + timestamp + ".xls";

        System.out.println();
        System.out.println("  [INFO] Generating Excel summary report...");
        System.out.println("  [INFO] Format : .xls  (Apache POI HSSFWorkbook)");

        // HSSFWorkbook = .xls format — works with poi-5.2.5.jar, no extra jars needed
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {

            // Create the 4 sheets inside the workbook
            createDashboardSheet(workbook);
            createNettingSheet(workbook);
            createReconciliationSheet(workbook);
            createNpciBalancesSheet(workbook);

            // Write the workbook to the file
            try (FileOutputStream fos = new FileOutputStream(fileName)) {
                workbook.write(fos);
            }

            System.out.println("  [SUCCESS] Report saved to : " + fileName);
            System.out.println("  [INFO]    Open the .xls file in Microsoft Excel or LibreOffice Calc.");
            System.out.println();

        } catch (IOException e) {
            System.out.println("  [ERROR] Failed to write report file: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("  [ERROR] Report generation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------------
    // SHEET 1: DASHBOARD SUMMARY
    // -----------------------------------------------------------------------

    /**
     * Creates the "Dashboard Summary" sheet.
     * Shows record counts and total amounts from every important table.
     */
    private static void createDashboardSheet(HSSFWorkbook workbook) {

        Sheet sheet = workbook.createSheet("Dashboard Summary");
        sheet.setColumnWidth(0, 10000);
        sheet.setColumnWidth(1, 6000);
        sheet.setColumnWidth(2, 7000);

        CellStyle titleStyle   = makeTitleStyle(workbook);
        CellStyle headerStyle  = makeHeaderStyle(workbook);
        CellStyle normalStyle  = makeNormalStyle(workbook);
        CellStyle amountStyle  = makeAmountStyle(workbook);
        CellStyle sectionStyle = makeSectionStyle(workbook);

        int rowNum = 0;

        // Title row
        Row titleRow = sheet.createRow(rowNum++);
        titleRow.setHeightInPoints(28);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("BANK SETTLEMENT SYSTEM — SUMMARY REPORT");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));

        // Generated-at row
        Row genRow = sheet.createRow(rowNum++);
        Cell genCell = genRow.createCell(0);
        genCell.setCellValue("Generated at : " + LocalDateTime.now().format(DISPLAY_DATE_FMT));
        genCell.setCellStyle(normalStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 2));

        rowNum++; // blank gap

        // --- INGESTION SUMMARY ---
        addSectionHeader(sheet, rowNum++, "INGESTION SUMMARY", sectionStyle);
        addHeaderRow(sheet, rowNum++, headerStyle, "TABLE / METRIC", "COUNT", "NOTES");

        addDataRow(sheet, rowNum++, normalStyle, "Incoming Transactions (Total)",
                countRecords("incoming_transaction"), "");
        addDataRow(sheet, rowNum++, normalStyle, "  - Status: QUEUED",
                countByStatus("incoming_transaction", "processing_status", "QUEUED"),
                "Waiting for dispatch");
        addDataRow(sheet, rowNum++, normalStyle, "  - Status: PROCESSED",
                countByStatus("incoming_transaction", "processing_status", "PROCESSED"),
                "Dispatched successfully");
        addDataRow(sheet, rowNum++, normalStyle, "  - Status: DUPLICATE",
                countByStatus("incoming_transaction", "processing_status", "DUPLICATE"),
                "Skipped as duplicates");

        rowNum++; // blank gap

        // --- DISPATCHED TRANSACTIONS ---
        addSectionHeader(sheet, rowNum++, "DISPATCHED TRANSACTIONS", sectionStyle);
        addHeaderRow(sheet, rowNum++, headerStyle, "TRANSACTION TYPE", "COUNT", "TOTAL AMOUNT (INR)");

        int    creditCount   = countRecords("credit_transaction");
        BigDecimal creditAmt = sumAmount("credit_transaction", "amount");

        int    debitCount    = countRecords("debit_transaction");
        BigDecimal debitAmt  = sumAmount("debit_transaction", "amount");

        int    ibCount       = countRecords("interbank_transaction");
        BigDecimal ibAmt     = sumAmount("interbank_transaction", "amount");

        int    revCount      = countRecords("reversal_transaction");
        BigDecimal revAmt    = sumAmount("reversal_transaction", "amount");

        BigDecimal grandTotal = creditAmt.add(debitAmt).add(ibAmt).add(revAmt);

        addAmountRow(sheet, rowNum++, normalStyle, amountStyle, "Credit Transactions",    creditCount, creditAmt);
        addAmountRow(sheet, rowNum++, normalStyle, amountStyle, "Debit Transactions",     debitCount,  debitAmt);
        addAmountRow(sheet, rowNum++, normalStyle, amountStyle, "InterBank Transactions", ibCount,     ibAmt);
        addAmountRow(sheet, rowNum++, normalStyle, amountStyle, "Reversal Transactions",  revCount,    revAmt);
        addAmountRow(sheet, rowNum++, headerStyle, amountStyle, "GRAND TOTAL",
                (creditCount + debitCount + ibCount + revCount), grandTotal);

        rowNum++; // blank gap

        // --- SETTLEMENT SUMMARY ---
        addSectionHeader(sheet, rowNum++, "SETTLEMENT SUMMARY", sectionStyle);
        addHeaderRow(sheet, rowNum++, headerStyle, "METRIC", "COUNT", "NOTES");

        addDataRow(sheet, rowNum++, normalStyle, "Total Settlement Batches",
                countRecords("settlement_batch"), "");
        addDataRow(sheet, rowNum++, normalStyle, "Total Settlement Records",
                countRecords("settlement_record"), "");
        addDataRow(sheet, rowNum++, normalStyle, "  - Settled Successfully",
                countByStatus("settlement_record", "settled_status", "SETTLED"), "");
        addDataRow(sheet, rowNum++, normalStyle, "  - Failed",
                countByStatus("settlement_record", "settled_status", "FAILED"),
                "Needs investigation");

        rowNum++; // blank gap

        // --- NETTING SUMMARY ---
        addSectionHeader(sheet, rowNum++, "NETTING SUMMARY", sectionStyle);
        addHeaderRow(sheet, rowNum++, headerStyle, "METRIC", "COUNT", "NOTES");
        addDataRow(sheet, rowNum++, normalStyle, "Netting Positions Computed",
                countRecords("netting_position"), "See Netting Positions sheet");

        rowNum++; // blank gap

        // --- RECONCILIATION SUMMARY ---
        addSectionHeader(sheet, rowNum++, "RECONCILIATION SUMMARY", sectionStyle);
        addHeaderRow(sheet, rowNum++, headerStyle, "STATUS", "COUNT", "NOTES");

        addDataRow(sheet, rowNum++, normalStyle, "Total Reconciliation Entries",
                countRecords("reconciliation_entry"), "");
        addDataRow(sheet, rowNum++, normalStyle, "  - MATCHED",
                countByStatus("reconciliation_entry", "recon_status", "MATCHED"),
                "Balances are correct");
        addDataRow(sheet, rowNum++, normalStyle, "  - UNMATCHED",
                countByStatus("reconciliation_entry", "recon_status", "UNMATCHED"),
                "Investigation needed !");
    }

    // -----------------------------------------------------------------------
    // SHEET 2: NETTING POSITIONS
    // -----------------------------------------------------------------------

    private static void createNettingSheet(HSSFWorkbook workbook) {

        Sheet sheet = workbook.createSheet("Netting Positions");
        sheet.setColumnWidth(0, 2000);
        sheet.setColumnWidth(1, 5500);
        sheet.setColumnWidth(2, 5500);
        sheet.setColumnWidth(3, 6500);
        sheet.setColumnWidth(4, 6500);
        sheet.setColumnWidth(5, 6500);
        sheet.setColumnWidth(6, 4500);
        sheet.setColumnWidth(7, 4000);

        CellStyle titleStyle  = makeTitleStyle(workbook);
        CellStyle headerStyle = makeHeaderStyle(workbook);
        CellStyle normalStyle = makeNormalStyle(workbook);
        CellStyle amountStyle = makeAmountStyle(workbook);

        int rowNum = 0;

        // Title
        Row titleRow = sheet.createRow(rowNum++);
        titleRow.setHeightInPoints(22);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("NETTING POSITIONS — BILATERAL NET SETTLEMENT");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 7));

        rowNum++; // blank

        // Column headers
        String[] headers = { "POS ID", "FROM BANK", "TO BANK",
                "GROSS DEBIT (INR)", "GROSS CREDIT (INR)", "NET AMOUNT (INR)",
                "DIRECTION", "DATE" };
        Row header = sheet.createRow(rowNum++);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Data rows from DB
        String sql =
            "SELECT position_id, from_bank_name, to_bank_name, " +
            "gross_debit_amount, gross_credit_amount, net_amount, direction, position_date " +
            "FROM netting_position ORDER BY position_id ASC";

        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Row row = sheet.createRow(rowNum++);
                createTextCell(row, 0, String.valueOf(rs.getLong("position_id")),    normalStyle);
                createTextCell(row, 1, safe(rs.getString("from_bank_name")),         normalStyle);
                createTextCell(row, 2, safe(rs.getString("to_bank_name")),           normalStyle);
                createNumCell (row, 3, rs.getBigDecimal("gross_debit_amount"),       amountStyle);
                createNumCell (row, 4, rs.getBigDecimal("gross_credit_amount"),      amountStyle);
                createNumCell (row, 5, rs.getBigDecimal("net_amount"),               amountStyle);
                createTextCell(row, 6, safe(rs.getString("direction")),              normalStyle);
                createTextCell(row, 7, safe(rs.getString("position_date")),          normalStyle);
            }
        } catch (SQLException e) {
            sheet.createRow(rowNum).createCell(0)
                 .setCellValue("ERROR loading data: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // SHEET 3: RECONCILIATION
    // -----------------------------------------------------------------------

    private static void createReconciliationSheet(HSSFWorkbook workbook) {

        Sheet sheet = workbook.createSheet("Reconciliation");
        sheet.setColumnWidth(0, 2000);
        sheet.setColumnWidth(1, 5500);
        sheet.setColumnWidth(2, 6500);
        sheet.setColumnWidth(3, 6500);
        sheet.setColumnWidth(4, 6500);
        sheet.setColumnWidth(5, 5000);
        sheet.setColumnWidth(6, 12000);

        CellStyle titleStyle     = makeTitleStyle(workbook);
        CellStyle headerStyle    = makeHeaderStyle(workbook);
        CellStyle normalStyle    = makeNormalStyle(workbook);
        CellStyle amountStyle    = makeAmountStyle(workbook);
        CellStyle unmatchedStyle = makeUnmatchedStyle(workbook);

        int rowNum = 0;

        // Title
        Row titleRow = sheet.createRow(rowNum++);
        titleRow.setHeightInPoints(22);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("RECONCILIATION — EXPECTED vs ACTUAL BALANCE");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 6));

        rowNum++;

        // Column headers
        String[] headers = { "ENTRY ID", "BANK NAME", "EXPECTED (INR)",
                "ACTUAL (INR)", "VARIANCE (INR)", "STATUS", "REMARKS" };
        Row header = sheet.createRow(rowNum++);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // JOIN to get bank_name properly
        String sql =
            "SELECT re.entry_id, b.bank_name, re.expected_amount, re.actual_amount, " +
            "re.variance, re.recon_status, re.remarks " +
            "FROM reconciliation_entry re " +
            "JOIN npci_bank_account na ON na.npci_account_id = re.account_id " +
            "JOIN bank b ON b.bank_id = na.bank_id " +
            "ORDER BY re.entry_id ASC";

        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Row row = sheet.createRow(rowNum++);
                String status = safe(rs.getString("recon_status"));

                // UNMATCHED rows get a red background style
                CellStyle rowStyle = "UNMATCHED".equals(status) ? unmatchedStyle : normalStyle;

                createTextCell(row, 0, String.valueOf(rs.getLong("entry_id")),  rowStyle);
                createTextCell(row, 1, safe(rs.getString("bank_name")),         rowStyle);
                createNumCell (row, 2, rs.getBigDecimal("expected_amount"),     amountStyle);
                createNumCell (row, 3, rs.getBigDecimal("actual_amount"),       amountStyle);
                createNumCell (row, 4, rs.getBigDecimal("variance"),            amountStyle);
                createTextCell(row, 5, status,                                  rowStyle);
                createTextCell(row, 6, safe(rs.getString("remarks")),           rowStyle);
            }
        } catch (SQLException e) {
            sheet.createRow(rowNum).createCell(0)
                 .setCellValue("ERROR loading data: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // SHEET 4: NPCI BANK BALANCES
    // -----------------------------------------------------------------------

    private static void createNpciBalancesSheet(HSSFWorkbook workbook) {

        Sheet sheet = workbook.createSheet("NPCI Bank Balances");
        sheet.setColumnWidth(0, 2500);
        sheet.setColumnWidth(1, 6000);
        sheet.setColumnWidth(2, 7000);
        sheet.setColumnWidth(3, 7000);
        sheet.setColumnWidth(4, 7000);

        CellStyle titleStyle  = makeTitleStyle(workbook);
        CellStyle headerStyle = makeHeaderStyle(workbook);
        CellStyle normalStyle = makeNormalStyle(workbook);
        CellStyle amountStyle = makeAmountStyle(workbook);

        int rowNum = 0;

        // Title
        Row titleRow = sheet.createRow(rowNum++);
        titleRow.setHeightInPoints(22);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("NPCI MEMBER BANK ACCOUNTS — BALANCE REPORT");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));

        rowNum++;

        // Column headers
        String[] headers = { "ACCOUNT ID", "BANK NAME", "OPENING BAL (INR)",
                "CURRENT BAL (INR)", "NET CHANGE (INR)" };
        Row header = sheet.createRow(rowNum++);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // JOIN to get bank_name
        String sql =
            "SELECT na.npci_account_id, b.bank_name, " +
            "na.opening_balance, na.current_balance " +
            "FROM npci_bank_account na " +
            "JOIN bank b ON b.bank_id = na.bank_id " +
            "ORDER BY na.npci_account_id ASC";

        BigDecimal totalOpening = BigDecimal.ZERO;
        BigDecimal totalCurrent = BigDecimal.ZERO;

        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                BigDecimal opening = rs.getBigDecimal("opening_balance");
                BigDecimal current = rs.getBigDecimal("current_balance");
                BigDecimal change  = BigDecimal.ZERO;

                if (opening != null && current != null) {
                    change = current.subtract(opening);
                    totalOpening = totalOpening.add(opening);
                    totalCurrent = totalCurrent.add(current);
                }

                Row row = sheet.createRow(rowNum++);
                createTextCell(row, 0, String.valueOf(rs.getLong("npci_account_id")), normalStyle);
                createTextCell(row, 1, safe(rs.getString("bank_name")),               normalStyle);
                createNumCell (row, 2, opening, amountStyle);
                createNumCell (row, 3, current, amountStyle);
                createNumCell (row, 4, change,  amountStyle);
            }

        } catch (SQLException e) {
            sheet.createRow(rowNum++).createCell(0)
                 .setCellValue("ERROR loading data: " + e.getMessage());
        }

        // Totals row at the bottom
        rowNum++;
        Row totalRow = sheet.createRow(rowNum);
        createTextCell(totalRow, 0, "",       headerStyle);
        createTextCell(totalRow, 1, "TOTAL",  headerStyle);
        createNumCell (totalRow, 2, totalOpening, makeAmountStyle(workbook));
        createNumCell (totalRow, 3, totalCurrent, makeAmountStyle(workbook));
        createNumCell (totalRow, 4, totalCurrent.subtract(totalOpening), makeAmountStyle(workbook));
    }

    // -----------------------------------------------------------------------
    // HELPER — CELL CREATION
    // -----------------------------------------------------------------------

    private static void createTextCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private static void createNumCell(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value.doubleValue() : 0.0);
        cell.setCellStyle(style);
    }

    private static void addSectionHeader(Sheet sheet, int rowNum, String title, CellStyle style) {
        Row row = sheet.createRow(rowNum);
        row.setHeightInPoints(16);
        Cell cell = row.createCell(0);
        cell.setCellValue(title);
        cell.setCellStyle(style);
        sheet.addMergedRegion(new CellRangeAddress(rowNum, rowNum, 0, 2));
    }

    private static void addHeaderRow(Sheet sheet, int rowNum,
            CellStyle style, String col1, String col2, String col3) {
        Row row = sheet.createRow(rowNum);
        createTextCell(row, 0, col1, style);
        createTextCell(row, 1, col2, style);
        createTextCell(row, 2, col3, style);
    }

    private static void addDataRow(Sheet sheet, int rowNum,
            CellStyle textStyle, String label, int count, String notes) {
        Row row = sheet.createRow(rowNum);
        createTextCell(row, 0, label,                textStyle);
        createTextCell(row, 1, String.valueOf(count), textStyle);
        createTextCell(row, 2, notes,                 textStyle);
    }

    private static void addAmountRow(Sheet sheet, int rowNum,
            CellStyle textStyle, CellStyle amtStyle,
            String label, int count, BigDecimal amount) {
        Row row = sheet.createRow(rowNum);
        createTextCell(row, 0, label,                textStyle);
        createTextCell(row, 1, String.valueOf(count), textStyle);
        createNumCell (row, 2, amount,               amtStyle);
    }

    // -----------------------------------------------------------------------
    // HELPER — DB QUERIES
    // -----------------------------------------------------------------------

    private static int countRecords(String tableName) {
        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + tableName);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) { /* return 0 below */ }
        return 0;
    }

    private static int countByStatus(String tableName, String col, String status) {
        String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE " + col + " = ?";
        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) { /* return 0 below */ }
        return 0;
    }

    private static BigDecimal sumAmount(String tableName, String col) {
        String sql = "SELECT COALESCE(SUM(" + col + "), 0) FROM " + tableName;
        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                BigDecimal result = rs.getBigDecimal(1);
                return result != null ? result : BigDecimal.ZERO;
            }
        } catch (SQLException e) { /* return zero below */ }
        return BigDecimal.ZERO;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    // -----------------------------------------------------------------------
    // HELPER — CELL STYLES (HSSFWorkbook versions)
    // -----------------------------------------------------------------------

    /** Bold large title — dark blue background, white text */
    private static CellStyle makeTitleStyle(HSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    /** Column header — grey background, white bold text */
    private static CellStyle makeHeaderStyle(HSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 10);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        return style;
    }

    /** Normal data row — plain, no background */
    private static CellStyle makeNormalStyle(HSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    /** Amount style — right aligned, comma + 2 decimal format */
    private static CellStyle makeAmountStyle(HSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        style.setBorderBottom(BorderStyle.THIN);
        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0.00"));
        style.setAlignment(HorizontalAlignment.RIGHT);
        return style;
    }

    /** Section divider — light blue background, bold text */
    private static CellStyle makeSectionStyle(HSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /** Unmatched row — light red background to draw attention */
    private static CellStyle makeUnmatchedStyle(HSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setFontHeightInPoints((short) 10);
        font.setColor(IndexedColors.DARK_RED.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }
}