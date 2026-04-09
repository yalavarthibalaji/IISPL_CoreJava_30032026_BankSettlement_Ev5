package com.iispl.connectionpool;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class ConnectionPool {

	private static HikariDataSource dataSource;
	private static final Object LOCK = new Object();

	private ConnectionPool() {
	}

	private static HikariDataSource getDataSource() {

		if (dataSource != null && !dataSource.isClosed()) {
			return dataSource;
		}

		synchronized (LOCK) {
			if (dataSource != null && !dataSource.isClosed()) {
				return dataSource;
			}

			try {
				Properties props = new Properties();
				InputStream in = ConnectionPool.class.getClassLoader().getResourceAsStream("db.properties");

				if (in == null) {
					throw new RuntimeException("db.properties not found in classpath.");
				}

				props.load(in);
				in.close();

				String url = props.getProperty("CONNECTION_STRING");
				String username = props.getProperty("USERNAME");
				String password = props.getProperty("PASSWORD");

				if (url == null || url.trim().isEmpty()) {
					throw new RuntimeException("CONNECTION_STRING is missing or empty in db.properties");
				}

				HikariConfig config = new HikariConfig();
				config.setJdbcUrl(url);
				config.setUsername(username);
				config.setPassword(password);

				config.setMaximumPoolSize(10);
				config.setMinimumIdle(2);

				config.setConnectionTimeout(30_000);
				config.setIdleTimeout(30_000);
				config.setMaxLifetime(1_800_000);

				config.setInitializationFailTimeout(0);

				config.setAutoCommit(true);
				config.addDataSourceProperty("prepStmtCacheSize", "250");
				config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
				config.addDataSourceProperty("cachePrepStmts", "true");
				config.addDataSourceProperty("useServerPrepStmts", "true");

				config.setPoolName("BankingPool");

				dataSource = new HikariDataSource(config);

			} catch (Exception e) {
				throw new RuntimeException("[ConnectionPool] Failed to initialize: " + e.getMessage(), e);
			}
		}

		return dataSource;
	}

	public static Connection getConnection() throws SQLException {
		return getDataSource().getConnection();
	}

	public static void shutdown() {
		synchronized (LOCK) {
			if (dataSource != null && !dataSource.isClosed()) {
				dataSource.close();
			}
		}
	}
}