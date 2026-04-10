package com.iispl.utility;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * XmlFileReader — Reads an RTGS XML batch file and extracts each individual
 * &lt;RTGSMessage&gt;...&lt;/RTGSMessage&gt; block as a standalone XML string
 * for RtgsAdapter.adapt().
 *
 * FILE FORMAT EXPECTED: A well-formed XML file with a &lt;RTGSBatch&gt; root
 * element containing one or more &lt;RTGSMessage&gt; child elements:
 *
 * &lt;?xml version="1.0"?&gt; &lt;RTGSBatch&gt; &lt;RTGSMessage&gt;
 * &lt;MsgId&gt;RTGS20260402001234&lt;/MsgId&gt; ... &lt;/RTGSMessage&gt;
 * &lt;RTGSMessage&gt; ... &lt;/RTGSMessage&gt; &lt;/RTGSBatch&gt;
 *
 * WHY NO XML LIBRARY? Consistent with the project's Core Java approach used in
 * RtgsAdapter. We read the entire file as a String and extract
 * &lt;RTGSMessage&gt; blocks using indexOf/substring — the same pattern as
 * extractXmlTag() in the adapter. This avoids javax.xml.parsers dependency and
 * keeps the codebase uniform.
 *
 * EACH EXTRACTED BLOCK looks like:
 * &lt;RTGSMessage&gt;&lt;MsgId&gt;RTGS20260402001234&lt;/MsgId&gt;...&lt;/RTGSMessage&gt;
 * This is exactly what RtgsAdapter.adapt() expects.
 *
 * COMMENT LINES (starting with &lt;!--) inside the XML are left in place — they
 * do not affect extraction since we key on &lt;RTGSMessage&gt; tags only.
 */
public class XmlFileReader implements TransactionFileReader {
    private static final Logger LOGGER = Logger.getLogger(XmlFileReader.class.getName());

	private static final String OPEN_TAG = "<RTGSMessage>";
	private static final String CLOSE_TAG = "</RTGSMessage>";

	@Override
	public List<String> readLines(String filePath) throws IOException {

		List<String> payloads = new ArrayList<>();

		// Read the entire XML file into one String
		String content = new String(Files.readAllBytes(Paths.get(filePath)));

		int searchFrom = 0;
		int recordCount = 0;

		while (true) {
			// Find the next opening tag
			int openIndex = content.indexOf(OPEN_TAG, searchFrom);
			if (openIndex == -1) {
				break; // No more RTGSMessage blocks
			}

			// Find the matching closing tag
			int closeIndex = content.indexOf(CLOSE_TAG, openIndex);
			if (closeIndex == -1) {
                PhaseLogger.getLogger().warning("[XmlFileReader] Unclosed RTGSMessage block. Stopping extraction.");
				break;
			}

			// Extract the complete block INCLUDING both tags
			int blockEnd = closeIndex + CLOSE_TAG.length();
			String block = content.substring(openIndex, blockEnd).trim();

			// Replace all newlines + indentation inside the block with nothing —
			// this converts multi-line XML to a single-line string.
			// RtgsAdapter's extractXmlTag() works on single-line or multi-line — both fine.
			// We keep it multi-line for readability in logs.
			payloads.add(block);

			recordCount++;

			// Move search position past this block
			searchFrom = blockEnd;
		}

        PhaseLogger.getLogger().info("[XmlFileReader] Total RTGS records extracted: " + payloads.size());
		return payloads;
	}

	@Override
	public String getSourceFormat() {
		return "RTGS_XML";
	}

	// Per-record XML logging intentionally removed to keep console clean.
}
