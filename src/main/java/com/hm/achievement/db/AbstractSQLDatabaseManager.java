package com.hm.achievement.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.apache.commons.lang3.StringUtils;

import com.hm.achievement.AdvancedAchievements;
import com.hm.achievement.category.MultipleAchievements;
import com.hm.achievement.category.NormalAchievements;
import com.hm.achievement.utils.Reloadable;

/**
 * Abstract class in charge of factoring out common functionality for the database manager.
 * 
 * @author Pyves
 */
public abstract class AbstractSQLDatabaseManager implements Reloadable {

	protected final AdvancedAchievements plugin;
	// Used to do some write operations to the database asynchronously.
	protected final ExecutorService pool;
	// Connection to the database; remains opened and shared.
	protected final AtomicReference<Connection> sqlConnection;

	protected volatile String databaseAddress;
	protected volatile String databaseUser;
	protected volatile String databasePassword;
	protected volatile String additionalConnectionOptions;
	protected volatile String prefix;

	private DateFormat dateFormat;
	private boolean configBookChronologicalOrder;

	public AbstractSQLDatabaseManager(AdvancedAchievements plugin) {
		this.plugin = plugin;
		// We expect to execute many short writes to the database. The pool can grow dynamically under high load and
		// allows to reuse threads.
		pool = Executors.newCachedThreadPool();
		sqlConnection = new AtomicReference<>();
	}

	@Override
	public void extractConfigurationParameters() {
		configBookChronologicalOrder = plugin.getPluginConfig().getBoolean("BookChronologicalOrder", true);
		String localeString = plugin.getPluginConfig().getString("DateLocale", "en");
		boolean dateDisplayTime = plugin.getPluginConfig().getBoolean("DateDisplayTime", false);
		Locale locale = new Locale(localeString);
		if (dateDisplayTime) {
			dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale);
		} else {
			dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale);
		}
	}

	/**
	 * Initialises the database system by extracting settings, performing setup tasks and updating schemas if necessary.
	 */
	public void initialise() {
		plugin.getLogger().info("Initialising database... ");

		prefix = plugin.getPluginConfig().getString("TablePrefix", "");
		additionalConnectionOptions = plugin.getPluginConfig().getString("AdditionalConnectionOptions", "");

		try {
			performPreliminaryTasks();
		} catch (ClassNotFoundException e) {
			plugin.getLogger().severe(
					"The JBDC library for your database type was not found. Please read the plugin's support for more information.");
			plugin.setSuccessfulLoad(false);
		}

		// Try to establish connection with database; stays opened until explicitly closed by the plugin.
		Connection conn = getSQLConnection();

		if (conn == null) {
			plugin.getLogger().severe("Could not establish SQL connection, disabling plugin.");
			plugin.getLogger().severe("Please verify your settings in the configuration file.");
			plugin.setOverrideDisable(true);
			plugin.getServer().getPluginManager().disablePlugin(plugin);
			return;
		}

		DatabaseUpdater databaseUpdater = new DatabaseUpdater(plugin, this);
		databaseUpdater.renameExistingTables(databaseAddress);
		databaseUpdater.initialiseTables();
		databaseUpdater.updateOldDBToMaterial();
		databaseUpdater.updateOldDBToDates();
		databaseUpdater.updateOldDBMobnameSize();
	}

	/**
	 * Performs any needed tasks before opening a connection to the database.
	 * 
	 * @throws ClassNotFoundException
	 */
	protected abstract void performPreliminaryTasks() throws ClassNotFoundException;

	/**
	 * Shuts the thread pool down and closes connection to database.
	 */
	public void shutdown() {
		pool.shutdown();
		try {
			// Wait a few seconds for remaining tasks to execute.
			if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
				plugin.getLogger().warning("Some write operations were not sent to the database.");
			}
		} catch (InterruptedException e) {
			plugin.getLogger().log(Level.SEVERE, "Error awaiting for pool to terminate its tasks.", e);
			Thread.currentThread().interrupt();
		} finally {
			try {
				Connection connection = sqlConnection.get();
				if (connection != null) {
					connection.close();
				}
			} catch (SQLException e) {
				plugin.getLogger().log(Level.SEVERE, "Error while closing connection to database.", e);
			}
		}
	}

	/**
	 * Retrieves SQL connection to MySQL, PostgreSQL or SQLite database.
	 * 
	 * @return the cached SQL connection or a new one
	 */
	protected Connection getSQLConnection() {
		Connection oldConnection = sqlConnection.get();
		try {
			// Check if Connection was not previously closed.
			if (oldConnection == null || oldConnection.isClosed()) {
				Connection newConnection = createSQLConnection();
				if (!sqlConnection.compareAndSet(oldConnection, newConnection)) {
					newConnection.close();
				}
			}
		} catch (SQLException e) {
			plugin.getLogger().log(Level.SEVERE, "Error while attempting to retrieve connection to database: ", e);
			plugin.setSuccessfulLoad(false);
		}
		return sqlConnection.get();
	}

	/**
	 * Creates a new Connection object tothe database.
	 * 
	 * @return connection object to database
	 * @throws SQLException
	 */
	protected abstract Connection createSQLConnection() throws SQLException;

	/**
	 * Gets the list of all the achievements of a player, sorted by chronological or reverse ordering.
	 * 
	 * @param uuid
	 * @return array list with groups of 3 strings: achievement name, description and date
	 */
	public List<String> getPlayerAchievementsList(UUID uuid) {
		// Either oldest date to newest one or newest date to oldest one.
		String sql = "SELECT * FROM " + prefix + "achievements WHERE playername = '" + uuid + "' ORDER BY date "
				+ (configBookChronologicalOrder ? "ASC" : "DESC");
		return ((SQLReadOperation<List<String>>) () -> {
			List<String> achievementsList = new ArrayList<>();
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					// Remove eventual double quotes due to a bug in versions 3.0 to 3.0.2 where names containing single
					// quotes were inserted with two single quotes in the database.
					String achName = StringUtils.replace(rs.getString(2), "''", "'");
					String displayName = plugin.getAchievementsAndDisplayNames().get(achName);
					if (StringUtils.isNotBlank(displayName)) {
						achievementsList.add(displayName);
					} else {
						achievementsList.add(achName);
					}
					achievementsList.add(rs.getString(3));
					achievementsList.add(dateFormat.format(rs.getDate(4)));
				}
			}
			return achievementsList;
		}).executeOperation("SQL error while retrieving achievements");
	}

	/**
	 * Gets the list of names of all the achievements of a player.
	 * 
	 * @param uuid
	 * @return array list with Name parameters
	 */
	public List<String> getPlayerAchievementNamesList(UUID uuid) {
		String sql = "SELECT achievement FROM " + prefix + "achievements WHERE playername = '" + uuid + "'";
		return ((SQLReadOperation<List<String>>) () -> {
			List<String> achievementNamesList = new ArrayList<>();
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					// Check for names with single quotes but also two single quotes, due to a bug in versions 3.0 to
					// 3.0.2 where names containing single quotes were inserted with two single quotes in the database.
					achievementNamesList.add(StringUtils.replace(rs.getString(1), "''", "'"));
				}
			}
			return achievementNamesList;
		}).executeOperation("SQL error while retrieving achievement names");
	}

	/**
	 * Gets the date of reception of a specific achievement.
	 * 
	 * @param uuid
	 * @param achName
	 * @return date represented as a string
	 */
	public String getPlayerAchievementDate(UUID uuid, String achName) {
		// Check for names with single quotes but also two single quotes, due to a bug in versions 3.0 to 3.0.2
		// where names containing single quotes were inserted with two single quotes in the database.
		String sql = achName.contains("'")
				? "SELECT date FROM " + prefix + "achievements WHERE playername = '" + uuid
						+ "' AND (achievement = ? OR achievement = ?)"
				: "SELECT date FROM " + prefix + "achievements WHERE playername = '" + uuid + "' AND achievement = ?";
		return ((SQLReadOperation<String>) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setString(1, achName);
				if (achName.contains("'")) {
					ps.setString(2, StringUtils.replace(achName, "'", "''"));
				}
				ResultSet rs = ps.executeQuery();
				if (rs.next()) {
					return dateFormat.format(rs.getDate(1));
				}
			}
			return null;
		}).executeOperation("SQL error while retrieving achievement date");
	}

	/**
	 * Gets the total number of achievements received by every player; this method is provided as a convenience for
	 * other plugins.
	 * 
	 * @return map containing number of achievements for every players
	 */
	public Map<UUID, Integer> getPlayersAchievementsAmount() {
		String sql = "SELECT playername, COUNT(*) FROM " + prefix + "achievements GROUP BY playername";
		return ((SQLReadOperation<Map<UUID, Integer>>) () -> {
			Map<UUID, Integer> achievementAmounts = new HashMap<>();
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					achievementAmounts.put(UUID.fromString(rs.getString(1)), rs.getInt(2));
				}
			}
			return achievementAmounts;
		}).executeOperation("SQL error while counting all player achievements");
	}

	/**
	 * Gets the total number of achievements received by a player, using an UUID.
	 * 
	 * @param uuid
	 * @return number of achievements
	 */
	public int getPlayerAchievementsAmount(UUID uuid) {
		String sql = "SELECT COUNT(*) FROM " + prefix + "achievements WHERE playername = '" + uuid + "'";
		return ((SQLReadOperation<Integer>) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}).executeOperation("SQL error while counting player's achievements");
	}

	/**
	 * Gets the list of players with the most achievements over a given period.
	 * 
	 * @param length
	 * @param start
	 * @return list with player UUIDs
	 */
	public List<String> getTopList(int length, long start) {
		// Either consider all the achievements or only those received after the start date.
		String sql = start == 0L
				? "SELECT playername, COUNT(*) FROM " + prefix
						+ "achievements GROUP BY playername ORDER BY COUNT(*) DESC LIMIT " + length
				: "SELECT playername, COUNT(*) FROM " + prefix
						+ "achievements WHERE date > ? GROUP BY playername ORDER BY COUNT(*) DESC LIMIT " + length;
		return ((SQLReadOperation<List<String>>) () -> {
			List<String> topList = new ArrayList<>(2 * length);
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				if (start > 0L) {
					ps.setDate(1, new java.sql.Date(start));
				}
				ResultSet rs = ps.executeQuery();
				while (rs.next()) {
					topList.add(rs.getString(1));
					topList.add(Integer.toString(rs.getInt(2)));
				}
			}
			return topList;
		}).executeOperation("SQL error while retrieving top players");
	}

	/**
	 * Gets number of players who have received at least one achievement after start date.
	 * 
	 * @param start
	 * @return list with player UUIDs
	 */
	public int getTotalPlayers(long start) {
		// Either consider all the achievements or only those received after the start date.
		String sql = start == 0L
				? "SELECT COUNT(*) FROM (SELECT DISTINCT playername  FROM " + prefix
						+ "achievements) AS distinctPlayers"
				: "SELECT COUNT(*) FROM (SELECT DISTINCT playername  FROM " + prefix
						+ "achievements WHERE date > ?) AS distinctPlayers";
		return ((SQLReadOperation<Integer>) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				if (start > 0L) {
					ps.setDate(1, new java.sql.Date(start));
				}
				ResultSet rs = ps.executeQuery();
				rs.next();
				return rs.getInt(1);
			}
		}).executeOperation("SQL error while retrieving total players");
	}

	/**
	 * Gets the rank of a player given his number of achievements received after start date.
	 * 
	 * @param uuid
	 * @param start
	 * @return player's rank
	 */
	public int getPlayerRank(UUID uuid, long start) {
		String sql;
		if (start == 0L) {
			// We consider all the achievements; no date comparison.
			sql = "SELECT COUNT(*) FROM (SELECT COUNT(*) number FROM " + prefix
					+ "achievements GROUP BY playername) AS achGroupedByPlayer WHERE number > (SELECT COUNT(*) FROM "
					+ prefix + "achievements WHERE playername = '" + uuid + "')";
		} else {
			// We only consider achievement received after the start date; do date comparisons.
			sql = "SELECT COUNT(*) FROM (SELECT COUNT(*) number FROM " + prefix
					+ "achievements WHERE date > ? GROUP BY playername) AS achGroupedByPlayer WHERE number > (SELECT COUNT(*) FROM "
					+ prefix + "achievements WHERE playername = '" + uuid + "' AND date > ?)";
		}
		return ((SQLReadOperation<Integer>) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				if (start > 0L) {
					ps.setDate(1, new java.sql.Date(start));
					ps.setDate(2, new java.sql.Date(start));
				}
				ResultSet rs = ps.executeQuery();
				rs.next();
				// Rank of a player corresponds to number of players with more achievements + 1.
				return rs.getInt(1) + 1;
			}
		}).executeOperation("SQL error while retrieving player rank");
	}

	/**
	 * Registers a new achievement for a player; this method will distinguish between asynchronous and synchronous
	 * processing.
	 * 
	 * @param uuid
	 * @param achName
	 * @param achMessage
	 */
	public void registerAchievement(UUID uuid, String achName, String achMessage) {
		String sql = "REPLACE INTO " + prefix + "achievements VALUES ('" + uuid + "',?,?,?)";
		((SQLWriteOperation) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setString(1, achName);
				ps.setString(2, achMessage);
				ps.setDate(3, new java.sql.Date(new java.util.Date().getTime()));
				ps.execute();
			}
		}).executeOperation(pool, plugin.getLogger(), "SQL error while registering achievement");
	}

	/**
	 * Checks whether player has received a specific achievement. Access through PoolsManager.
	 * 
	 * @param uuid
	 * @param achName
	 * @return true if achievement found in database, false otherwise
	 */
	public boolean hasPlayerAchievement(UUID uuid, String achName) {
		// Check for names with single quotes but also two single quotes, due to a bug in versions 3.0 to 3.0.2
		// where names containing single quotes were inserted with two single quotes in the database.
		String sql = achName.contains("'")
				? "SELECT achievement FROM " + prefix + "achievements WHERE playername = '" + uuid
						+ "' AND (achievement = ? OR achievement = ?)"
				: "SELECT achievement FROM " + prefix + "achievements WHERE playername = '" + uuid
						+ "' AND achievement = ?";
		return ((SQLReadOperation<Boolean>) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setString(1, achName);
				if (achName.contains("'")) {
					ps.setString(2, StringUtils.replace(achName, "'", "''"));
				}
				return ps.executeQuery().next();
			}
		}).executeOperation("SQL error while checking achievement");
	}

	/**
	 * Gets the amount of a NormalAchievement statistic.
	 * 
	 * @param uuid
	 * @param category
	 * @return statistic
	 */
	public long getNormalAchievementAmount(UUID uuid, NormalAchievements category) {
		String dbName = category.toDBName();
		String sql = "SELECT " + dbName + " FROM " + prefix + dbName + " WHERE playername = '" + uuid + "'";
		return ((SQLReadOperation<Long>) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return rs.getLong(dbName);
				}
			}
			return 0L;
		}).executeOperation("SQL error while retrieving " + dbName + " stats");
	}

	/**
	 * Gets the amount of a MultipleAchievement statistic.
	 * 
	 * @param uuid
	 * @param category
	 * @param subcategory
	 * @return statistic
	 */
	public long getMultipleAchievementAmount(UUID uuid, MultipleAchievements category, String subcategory) {
		String dbName = category.toDBName();
		String sql = "SELECT " + dbName + " FROM " + prefix + dbName + " WHERE playername = '" + uuid + "' AND "
				+ category.toSubcategoryDBName() + " = ?";
		return ((SQLReadOperation<Long>) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setString(1, subcategory);
				ResultSet rs = ps.executeQuery();
				if (rs.next()) {
					return rs.getLong(dbName);
				}
			}
			return 0L;
		}).executeOperation("SQL error while retrieving " + dbName + " stats");
	}

	/**
	 * Returns player's number of connections on separate days (used by GUI).
	 * 
	 * @param uuid
	 * @return connections statistic
	 */
	public int getConnectionsAmount(UUID uuid) {
		String dbName = NormalAchievements.CONNECTIONS.toDBName();
		String sql = "SELECT " + dbName + " FROM " + prefix + dbName + " WHERE playername = '" + uuid + "'";
		return ((SQLReadOperation<Integer>) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return rs.getInt(dbName);
				}
			}
			return 0;
		}).executeOperation("SQL error while retrieving connection statistics");
	}

	/**
	 * Gets a player's last connection date.
	 * 
	 * @param uuid
	 * @return String with date
	 */
	public String getPlayerConnectionDate(UUID uuid) {
		String dbName = NormalAchievements.CONNECTIONS.toDBName();
		String sql = "SELECT date FROM " + prefix + dbName + " WHERE playername = '" + uuid + "'";
		return ((SQLReadOperation<String>) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return rs.getString("date");
				}
			}
			return null;
		}).executeOperation("SQL error while retrieving connection date stats");
	}

	/**
	 * Updates player's number of connections and last connection date and returns number of connections (used by
	 * Connections listener).
	 * 
	 * @param uuid
	 * @param date
	 * @return connections statistic
	 */
	public int updateAndGetConnection(UUID uuid, String date) {
		String dbName = NormalAchievements.CONNECTIONS.toDBName();
		String sqlRead = "SELECT " + dbName + " FROM " + prefix + dbName + " WHERE playername = ?";
		return ((SQLReadOperation<Integer>) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sqlRead)) {
				ps.setString(1, uuid.toString());
				int connections = 1;
				ResultSet rs = ps.executeQuery();
				if (rs.next()) {
					connections += rs.getInt(dbName);
				}
				String sqlWrite = "REPLACE INTO " + prefix + dbName + " VALUES ('" + uuid + "', " + connections
						+ ", ?)";
				((SQLWriteOperation) () -> {
					Connection writeConn = getSQLConnection();
					try (PreparedStatement writePrep = writeConn.prepareStatement(sqlWrite)) {
						writePrep.setString(1, date);
						writePrep.execute();
					}
				}).executeOperation(pool, plugin.getLogger(), "SQL error while updating connection");
				return connections;
			}
		}).executeOperation("SQL error while handling connection event");
	}

	/**
	 * Deletes an achievement from a player.
	 * 
	 * @param uuid
	 * @param achName
	 */
	public void deletePlayerAchievement(UUID uuid, String achName) {
		// Check for names with single quotes but also two single quotes, due to a bug in versions 3.0 to 3.0.2
		// where names containing single quotes were inserted with two single quotes in the database.
		String sql = achName.contains("'")
				? "DELETE FROM " + prefix + "achievements WHERE playername = '" + uuid
						+ "' AND (achievement = ? OR achievement = ?)"
				: "DELETE FROM " + prefix + "achievements WHERE playername = '" + uuid + "' AND achievement = ?";
		((SQLWriteOperation) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setString(1, achName);
				if (achName.contains("'")) {
					ps.setString(2, StringUtils.replace(achName, "'", "''"));
				}
				ps.execute();
			}
		}).executeOperation(pool, plugin.getLogger(), "SQL error while deleting achievement");
	}

	/**
	 * Clear Connection statistics for a given player.
	 * 
	 * @param uuid
	 */
	public void clearConnection(UUID uuid) {
		String sql = "DELETE FROM " + prefix + "connections WHERE playername = '" + uuid + "'";
		((SQLWriteOperation) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.execute();
			}
		}).executeOperation(pool, plugin.getLogger(), "SQL error while deleting connections");
	}

	protected String getPrefix() {
		return prefix;
	}
}