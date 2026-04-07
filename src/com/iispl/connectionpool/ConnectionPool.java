package com.iispl.connectionpool;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * ConnectionPool — Singleton HikariCP connection pool for Supabase PostgreSQL.
 *
 * WHY NOT A STATIC BLOCK ANYMORE? The original code used a static block to
 * initialize HikariDataSource. If the DB connection fails inside a static
 * block, Java marks the class as PERMANENTLY BROKEN for the rest of the JVM
 * session. Every future access to that class throws NoClassDefFoundError — even
 * in other threads.
 *
 * This was causing the error: "NoClassDefFoundError: Could not initialize class
 * ConnectionPool"
 *
 * The fix: use lazy initialization inside a synchronized getInstance() method
 * instead of a static block. If connection fails, it throws a clear
 * RuntimeException immediately — it does NOT permanently poison the class.
 *
 * HOW TO USE: Connection conn = ConnectionPool.getConnection(); ... use conn
 * ... conn.close(); // returns it to the pool
 *
 * ConnectionPool.shutdown(); // call once at the very end
 */
public class ConnectionPool {

	// The single HikariDataSource instance — null until first getConnection() call
	private static HikariDataSource dataSource;

	// Lock object for thread-safe lazy initialization
	private static final Object LOCK = new Object();

	// -----------------------------------------------------------------------
	// Private constructor — nobody can do "new ConnectionPool()"
	// -----------------------------------------------------------------------
	private ConnectionPool() {
	}

	// -----------------------------------------------------------------------
	// getDataSource() — lazy, thread-safe initialization
	//
	// WHY synchronized?
	// Multiple worker threads call getConnection() at the same time.
	// Without synchronized, two threads could both see dataSource==null
	// and both try to create the pool — creating TWO pools. Bad!
	// synchronized(LOCK) ensures only ONE thread initializes the pool.
	// -----------------------------------------------------------------------
	private static HikariDataSource getDataSource() {

		// First check without locking — fast path for when pool is already ready
		if (dataSource != null && !dataSource.isClosed()) {
			return dataSource;
		}

		// Second check inside lock — slow path for first-time initialization
		synchronized (LOCK) {
			// Check again inside the lock (another thread may have initialized
			// it between our first check and acquiring the lock)
			if (dataSource != null && !dataSource.isClosed()) {
				return dataSource;
			}

			// --- Initialize the pool ---
			try {
				Properties props = new Properties();
				InputStream in = ConnectionPool.class.getClassLoader().getResourceAsStream("db.properties");

				if (in == null) {
					throw new RuntimeException("db.properties not found in classpath. "
							+ "Make sure resources/ folder is on the build path in Eclipse. "
							+ "Right-click resources/ → Build Path → Use as Source Folder.");
				}

				props.load(in);
				in.close();

				String url = props.getProperty("CONNECTION_STRING");
				String username = props.getProperty("USERNAME");
				String password = props.getProperty("PASSWORD");

				if (url == null || url.trim().isEmpty()) {
					throw new RuntimeException("CONNECTION_STRING is missing or empty in db.properties");
				}

				System.out.println("[ConnectionPool] Connecting to: " + url);
				System.out.println("[ConnectionPool] Username: " + username);

				HikariConfig config = new HikariConfig();
				config.setJdbcUrl(url);
				config.setUsername(username);
				config.setPassword(password);

				// Pool sizing
				config.setMaximumPoolSize(10);
				config.setMinimumIdle(2);

				// Timeouts (ms)
				config.setConnectionTimeout(30_000); // 30 sec to get a connection
				config.setIdleTimeout(30_000);
				config.setMaxLifetime(1_800_000); // 30 min — rotate connections

				// initializationFailTimeout = 0 means:
				// "Don't fail the pool creation if initial connections can't be made.
				// Try to connect lazily when getConnection() is first called."
				// This prevents the pool itself from throwing during construction.
				config.setInitializationFailTimeout(0);

				config.setAutoCommit(true);
				config.addDataSourceProperty("prepStmtCacheSize", "250");
				config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
				config.addDataSourceProperty("cachePrepStmts", "true");
				config.addDataSourceProperty("useServerPrepStmts", "true");

				config.setPoolName("BankingPool");

				dataSource = new HikariDataSource(config);

				System.out.println("[ConnectionPool] Pool initialized successfully.");

			} catch (Exception e) {
				// Throw RuntimeException — NOT ExceptionInInitializerError.
				// RuntimeException does NOT permanently poison the class.
				// The caller (DAO method) will catch this and log it properly.
				throw new RuntimeException("[ConnectionPool] Failed to initialize: " + e.getMessage()
						+ "\n>>> CHECK: Is your Supabase project active? "
						+ "Go to supabase.com → your project → make sure it is not paused."
						+ "\n>>> CHECK: Is the password in db.properties correct?"
						+ "\n>>> CHECK: Are you connected to the internet?", e);
			}
		}

		return dataSource;
	}

	// -----------------------------------------------------------------------
	// getConnection() — public API used by all DAO classes
	// -----------------------------------------------------------------------

	/**
	 * Returns a Connection from the pool. Always call conn.close() after use — this
	 * returns it to the pool, it does NOT actually close the physical connection.
	 *
	 * @return A live JDBC Connection to Supabase
	 * @throws SQLException if the pool cannot provide a connection
	 */
	public static Connection getConnection() throws SQLException {
		return getDataSource().getConnection();
	}

	// -----------------------------------------------------------------------
	// shutdown() — call once at program end
	// -----------------------------------------------------------------------

	/**
	 * Closes all connections in the pool. Call this only after ALL threads have
	 * finished their DB work. After this is called, getConnection() will fail.
	 */
	public static void shutdown() {
		synchronized (LOCK) {
			if (dataSource != null && !dataSource.isClosed()) {
				dataSource.close();
				System.out.println("[ConnectionPool] Pool shut down — all connections closed.");
			}
		}
	}
}