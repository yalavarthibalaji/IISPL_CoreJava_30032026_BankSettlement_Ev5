package com.iispl.banksettlement.enums;

/**
 * Communication protocol used by each source system to send transactions.
 */
public enum ProtocolType {
	REST_API, // HTTP/HTTPS REST endpoints (webhooks)
	FLAT_FILE, // File-drop based (CSV, XML, fixed-width)
	MESSAGE_QUEUE, // MQ-based (e.g., IBM MQ, RabbitMQ)
	SFTP, // Secure File Transfer Protocol
	DIRECT_DB // Direct database connection
}
