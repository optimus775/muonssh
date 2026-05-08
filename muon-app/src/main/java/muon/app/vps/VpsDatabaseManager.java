package muon.app.vps;

import lombok.extern.slf4j.Slf4j;
import muon.app.App;
import muon.app.util.Constants;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public final class VpsDatabaseManager {

    private static final int CURRENT_SCHEMA = 3;

    private VpsDatabaseManager() {
    }

    public static Connection openConnection() throws SQLException {
        File dbFile = new File(App.getCONTEXT().getConfigDir(), Constants.VPS_LEDGER_DB_FILE);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    public static synchronized void migrate() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
            int version = getCurrentVersion(statement);
            if (version < 1) {
                log.info("Applying VPS Ledger schema migration 1");
                statement.execute("CREATE TABLE IF NOT EXISTS app_state (key TEXT PRIMARY KEY, value TEXT)");
                statement.execute("CREATE TABLE IF NOT EXISTS folders ("
                        + "id TEXT PRIMARY KEY, "
                        + "parent_id TEXT, "
                        + "name TEXT NOT NULL, "
                        + "position INTEGER NOT NULL DEFAULT 0, "
                        + "updated_at INTEGER NOT NULL, "
                        + "FOREIGN KEY(parent_id) REFERENCES folders(id) ON DELETE CASCADE"
                        + ")");
                statement.execute("CREATE TABLE IF NOT EXISTS hosts ("
                        + "id TEXT PRIMARY KEY, "
                        + "name TEXT, "
                        + "host TEXT, "
                        + "provider TEXT, "
                        + "provider_url TEXT, "
                        + "account_id TEXT, "
                        + "billing_cycle TEXT, "
                        + "billing_cycle_days INTEGER, "
                        + "price TEXT, "
                        + "currency TEXT, "
                        + "next_payment_date TEXT, "
                        + "cancel_by_date TEXT, "
                        + "auto_pay INTEGER NOT NULL DEFAULT 0, "
                        + "status TEXT, "
                        + "tags TEXT, "
                        + "description TEXT, "
                        + "external_refs TEXT, "
                        + "vikunja_task_id INTEGER, "
                        + "sync_private_key INTEGER NOT NULL DEFAULT 0, "
                        + "sync_public_key INTEGER NOT NULL DEFAULT 0, "
                        + "deleted INTEGER NOT NULL DEFAULT 0, "
                        + "updated_at INTEGER NOT NULL, "
                        + "session_json TEXT NOT NULL"
                        + ")");
                statement.execute("CREATE TABLE IF NOT EXISTS folder_hosts ("
                        + "folder_id TEXT NOT NULL, "
                        + "host_id TEXT NOT NULL, "
                        + "position INTEGER NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY(folder_id, host_id), "
                        + "FOREIGN KEY(folder_id) REFERENCES folders(id) ON DELETE CASCADE, "
                        + "FOREIGN KEY(host_id) REFERENCES hosts(id) ON DELETE CASCADE"
                        + ")");
                statement.execute("CREATE TABLE IF NOT EXISTS sync_conflicts ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "host_id TEXT, "
                        + "local_json TEXT, "
                        + "remote_json TEXT, "
                        + "created_at INTEGER NOT NULL"
                        + ")");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_hosts_provider ON hosts(provider)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_hosts_status ON hosts(status)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_hosts_next_payment ON hosts(next_payment_date)");
                statement.execute("INSERT INTO schema_migrations(version, applied_at) VALUES (1, " + System.currentTimeMillis() + ")");
                version = 1;
            }
            if (version < 2) {
                log.info("Applying VPS Ledger schema migration 2");
                statement.execute("CREATE TABLE IF NOT EXISTS providers ("
                        + "id TEXT PRIMARY KEY, "
                        + "name TEXT NOT NULL, "
                        + "website TEXT, "
                        + "panel_url TEXT, "
                        + "billing_url TEXT, "
                        + "account_id TEXT, "
                        + "notes TEXT, "
                        + "tags TEXT, "
                        + "updated_at INTEGER NOT NULL, "
                        + "deleted INTEGER NOT NULL DEFAULT 0"
                        + ")");
                addColumnIfMissing(connection, "hosts", "provider_id", "TEXT");
                addColumnIfMissing(connection, "hosts", "billing_period_type", "TEXT");
                addColumnIfMissing(connection, "hosts", "billing_period_days", "INTEGER");
                addColumnIfMissing(connection, "hosts", "hourly_rate", "TEXT");
                addColumnIfMissing(connection, "hosts", "next_balance_check_date", "TEXT");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_providers_name_unique ON providers(name COLLATE NOCASE) WHERE deleted = 0");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_hosts_provider_id ON hosts(provider_id)");

                long now = System.currentTimeMillis();
                statement.execute("INSERT OR IGNORE INTO providers("
                        + "id, name, website, panel_url, billing_url, account_id, notes, tags, updated_at, deleted"
                        + ") "
                        + "SELECT lower(hex(randomblob(16))), trim(provider), "
                        + "min(NULLIF(provider_url, '')), min(NULLIF(provider_url, '')), min(NULLIF(provider_url, '')), "
                        + "min(NULLIF(account_id, '')), NULL, NULL, " + now + ", 0 "
                        + "FROM hosts "
                        + "WHERE provider IS NOT NULL AND trim(provider) <> '' "
                        + "GROUP BY lower(trim(provider))");
                statement.execute("UPDATE hosts SET provider_id = ("
                        + "SELECT p.id FROM providers p "
                        + "WHERE lower(p.name) = lower(trim(hosts.provider)) AND p.deleted = 0 "
                        + "LIMIT 1"
                        + ") WHERE (provider_id IS NULL OR provider_id = '') "
                        + "AND provider IS NOT NULL AND trim(provider) <> ''");
                statement.execute("UPDATE hosts SET billing_period_type = COALESCE(NULLIF(billing_period_type, ''), 'fixed_period')");
                statement.execute("UPDATE hosts SET billing_period_days = COALESCE(billing_period_days, billing_cycle_days)");
                statement.execute("INSERT INTO schema_migrations(version, applied_at) VALUES (2, " + now + ")");
                version = 2;
            }
            if (version < 3) {
                log.info("Applying VPS Ledger schema migration 3");
                statement.execute("DROP INDEX IF EXISTS idx_providers_name_unique");
                statement.execute("DROP TABLE IF EXISTS providers_v3");
                statement.execute("CREATE TABLE IF NOT EXISTS providers_v3 ("
                        + "id TEXT PRIMARY KEY, "
                        + "name TEXT NOT NULL, "
                        + "slug TEXT NOT NULL, "
                        + "website TEXT, "
                        + "account_id TEXT, "
                        + "notes TEXT, "
                        + "tags TEXT, "
                        + "updated_at INTEGER NOT NULL, "
                        + "deleted INTEGER NOT NULL DEFAULT 0"
                        + ")");
                migrateProvidersToSchema3(connection);
                statement.execute("DROP TABLE providers");
                statement.execute("ALTER TABLE providers_v3 RENAME TO providers");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_providers_name_unique ON providers(name COLLATE NOCASE) WHERE deleted = 0");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_providers_slug_unique ON providers(slug COLLATE NOCASE) WHERE deleted = 0");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_hosts_provider_id ON hosts(provider_id)");
                statement.execute("INSERT INTO schema_migrations(version, applied_at) VALUES (3, " + System.currentTimeMillis() + ")");
            }
        }
    }

    private static void migrateProvidersToSchema3(Connection connection) throws SQLException {
        Set<String> usedSlugs = new HashSet<>();
        try (Statement select = connection.createStatement();
             ResultSet rs = select.executeQuery("SELECT * FROM providers ORDER BY updated_at, LOWER(name), id");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO providers_v3(id, name, slug, website, account_id, notes, tags, updated_at, deleted) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            while (rs.next()) {
                String slug = ProviderSlug.unique(rs.getString("name"), usedSlugs);
                insert.setString(1, rs.getString("id"));
                insert.setString(2, rs.getString("name"));
                insert.setString(3, slug);
                insert.setString(4, rs.getString("website"));
                insert.setString(5, rs.getString("account_id"));
                insert.setString(6, rs.getString("notes"));
                insert.setString(7, rs.getString("tags"));
                insert.setLong(8, rs.getLong("updated_at"));
                insert.setInt(9, rs.getInt("deleted"));
                insert.executeUpdate();
            }
        }
    }

    private static void addColumnIfMissing(Connection connection, String table, String column, String definition) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private static int getCurrentVersion(Statement statement) {
        try (ResultSet resultSet = statement.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
            if (resultSet.next()) {
                return resultSet.getInt(1);
            }
        } catch (SQLException e) {
            log.error("Unable to read VPS Ledger schema version", e);
        }
        return 0;
    }

    public static int getCurrentSchema() {
        return CURRENT_SCHEMA;
    }
}
