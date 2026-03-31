package com.iispl.connectionpool;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class ConnectionPool {
	private static final HikariDataSource dataSource;

	static {
		try {
			Properties props = new Properties();
			InputStream in = ConnectionPool.class.getClassLoader().getResourceAsStream("db.properties");

			if (in == null) {
				throw new RuntimeException("db.properties not found in classpath (resources/)");
			}

			props.load(in);

			String url = props.getProperty("CONNECTION_STRING");
			String username = props.getProperty("USERNAME");
			String password = props.getProperty("PASSWORD");

			HikariConfig config = new HikariConfig();
			config.setJdbcUrl(url);
			config.setUsername(username);
			config.setPassword(password);

			// Pool sizing
			config.setMaximumPoolSize(10);
			config.setMinimumIdle(2);

			// Timeouts (ms)
			config.setIdleTimeout(30_000);
			config.setConnectionTimeout(30_000);
			config.setMaxLifetime(1_800_000); // 30 min — rotate connections

			config.setAutoCommit(true);
			config.addDataSourceProperty("prepStmtCacheSize", "250");
			config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
			config.addDataSourceProperty("cachePrepStmts", "true");
			config.addDataSourceProperty("useServerPrepStmts", "true");

			// Pool name for monitoring / logs
			config.setPoolName("BankingPool");

			dataSource = new HikariDataSource(config);

		} catch (Exception e) {
			throw new ExceptionInInitializerError("Failed to initialise ConnectionPool: " + e.getMessage());
		}
	}

	// Prevent instantiation
	private ConnectionPool() {
	}

	public static HikariDataSource getDataSource() {
		return dataSource;
	}

	public static Connection getConnection() throws SQLException {
		return dataSource.getConnection();
	}

	public static void shutdown() {
		if (dataSource != null && !dataSource.isClosed()) {
			dataSource.close();
		}
	}
}
