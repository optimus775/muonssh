package muon.app.vps;

import junit.framework.TestCase;
import muon.app.App;
import muon.app.ui.components.session.HopEntry;
import muon.app.ui.components.session.SavedSessionTree;
import muon.app.ui.components.session.SessionFolder;
import muon.app.ui.components.session.SessionInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class VpsHostRepositoryTest extends TestCase {

    public void testSaveAndLoadTree() throws Exception {
        Path configDir = Files.createTempDirectory("vps-ledger-test");
        App.getCONTEXT().setConfigDir(configDir.toFile());

        SessionInfo info = new SessionInfo();
        info.setId("host-1");
        info.setName("test-vps");
        info.setHost("192.0.2.10");
        info.setUser("root");
        info.setProvider("Example Provider");
        info.setNextPaymentDate("2026-06-01");
        info.setTags("prod,cheap");

        SessionFolder folder = new SessionFolder();
        folder.setId("folder-1");
        folder.setName("My sites");
        folder.getItems().add(info);

        VpsHostRepository repository = new VpsHostRepository();
        repository.saveTree(folder, info.getId());
        SavedSessionTree loaded = repository.loadTree();

        assertEquals("My sites", loaded.getFolder().getName());
        assertEquals(1, loaded.getFolder().getItems().size());
        SessionInfo loadedInfo = loaded.getFolder().getItems().get(0);
        assertEquals("test-vps", loadedInfo.getName());
        assertEquals("Example Provider", loadedInfo.getProvider());
        assertNotNull(loadedInfo.getProviderId());
        assertEquals("2026-06-01", loadedInfo.getNextPaymentDate());
        assertEquals("prod,cheap", loadedInfo.getTags());
    }

    public void testSaveAndLoadHourlyBilling() throws Exception {
        Path configDir = Files.createTempDirectory("vps-ledger-hourly-test");
        App.getCONTEXT().setConfigDir(configDir.toFile());

        ProviderRecord provider = new ProviderRecord();
        provider.setName("Hourly Provider");
        provider.setWebsite("https://provider.example");
        provider = new VpsProviderRepository().upsertProvider(provider);

        SessionInfo info = new SessionInfo();
        info.setId("host-2");
        info.setName("hourly-vps");
        info.setHost("192.0.2.11");
        info.setUser("root");
        info.setProviderId(provider.getId());
        info.setBillingPeriodType("hourly_balance");
        info.setHourlyRate("0.01");
        info.setNextBalanceCheckDate("2026-06-10");

        SessionFolder folder = new SessionFolder();
        folder.setId("folder-2");
        folder.setName("My sites");
        folder.getItems().add(info);

        VpsHostRepository repository = new VpsHostRepository();
        repository.saveTree(folder, info.getId());
        SavedSessionTree loaded = repository.loadTree();

        SessionInfo loadedInfo = loaded.getFolder().getItems().get(0);
        assertEquals(provider.getId(), loadedInfo.getProviderId());
        assertEquals("Hourly Provider", loadedInfo.getProvider());
        assertEquals("https://provider.example", loadedInfo.getProviderUrl());
        assertEquals("hourly_balance", loadedInfo.getBillingPeriodType());
        assertEquals("0.01", loadedInfo.getHourlyRate());
        assertEquals("2026-06-10", loadedInfo.getNextBalanceCheckDate());
    }

    public void testMigrationToSchema3DropsLegacyProviderColumnsAndBackfillsSlug() throws Exception {
        Path configDir = Files.createTempDirectory("vps-ledger-schema3-test");
        App.getCONTEXT().setConfigDir(configDir.toFile());

        try (Connection connection = VpsDatabaseManager.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
            statement.execute("INSERT INTO schema_migrations(version, applied_at) VALUES (2, 1)");
            statement.execute("CREATE TABLE providers ("
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
            statement.execute("CREATE TABLE hosts ("
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
                    + "session_json TEXT NOT NULL, "
                    + "provider_id TEXT, "
                    + "billing_period_type TEXT, "
                    + "billing_period_days INTEGER, "
                    + "hourly_rate TEXT, "
                    + "next_balance_check_date TEXT"
                    + ")");
            statement.execute("INSERT INTO providers(id, name, website, panel_url, billing_url, account_id, notes, tags, updated_at, deleted) "
                    + "VALUES ('provider-1', 'Provider One', 'https://one.example', 'https://one.example/panel', 'https://one.example/billing', 'acct-1', 'n1', 't1', 10, 0)");
            statement.execute("INSERT INTO providers(id, name, website, panel_url, billing_url, account_id, notes, tags, updated_at, deleted) "
                    + "VALUES ('provider-2', 'Provider One', 'https://two.example', 'https://two.example/panel', 'https://two.example/billing', 'acct-2', 'n2', 't2', 11, 1)");
            statement.execute("INSERT INTO hosts(id, name, host, provider_id, provider, provider_url, billing_cycle, billing_cycle_days, price, currency, "
                    + "next_payment_date, auto_pay, status, updated_at, deleted, session_json) "
                    + "VALUES ('host-1', 'alpha', '192.0.2.10', 'provider-1', 'Provider One', 'https://one.example', 'monthly', 30, '5', 'USD', '2026-06-01', 0, 'active', 10, 0, '{}')");
        }

        VpsDatabaseManager.migrate();

        try (Connection connection = VpsDatabaseManager.openConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(providers)")) {
                boolean hasSlug = false;
                while (columns.next()) {
                    String columnName = columns.getString("name");
                    assertFalse("panel_url".equals(columnName));
                    assertFalse("billing_url".equals(columnName));
                    if ("slug".equals(columnName)) {
                        hasSlug = true;
                    }
                }
                assertTrue(hasSlug);
            }

            try (ResultSet rs = statement.executeQuery("SELECT slug, website FROM providers WHERE id = 'provider-1'")) {
                assertTrue(rs.next());
                assertEquals("provider-one", rs.getString("slug"));
                assertEquals("https://one.example", rs.getString("website"));
            }

            try (ResultSet rs = statement.executeQuery("SELECT slug FROM providers WHERE id = 'provider-2'")) {
                assertTrue(rs.next());
                assertEquals("provider-one-2", rs.getString("slug"));
            }

            try (ResultSet rs = statement.executeQuery("SELECT provider_id FROM hosts WHERE id = 'host-1'")) {
                assertTrue(rs.next());
                assertEquals("provider-1", rs.getString("provider_id"));
            }
        }
    }

    public void testRebuildFromLegacyJsonKeepsProvidersEmpty() throws Exception {
        Path configDir = Files.createTempDirectory("vps-ledger-legacy-rebuild");
        App.getCONTEXT().setConfigDir(configDir.toFile());

        String json = "{"
                + "\"folder\":{"
                + "\"id\":\"root-folder\","
                + "\"name\":\"My sites\","
                + "\"folders\":[],"
                + "\"items\":[{"
                + "\"id\":\"legacy-host\","
                + "\"name\":\"legacy-vps\","
                + "\"host\":\"192.0.2.16\","
                + "\"user\":\"root\","
                + "\"provider\":\"Legacy Provider\","
                + "\"providerUrl\":\"https://legacy.example\","
                + "\"port\":22,"
                + "\"favouriteRemoteFolders\":[],"
                + "\"favouriteLocalFolders\":[],"
                + "\"jumpHosts\":[],"
                + "\"portForwardingRules\":[],"
                + "\"useX11Forwarding\":false,"
                + "\"sftpOnly\":false"
                + "}]" 
                + "},"
                + "\"lastSelection\":\"legacy-host\""
                + "}";
        Path legacyJson = configDir.resolve("session-store.json");
        Files.writeString(legacyJson, json);

        VpsHostRepository repository = new VpsHostRepository();
        repository.rebuildFromLegacyJson(legacyJson.toFile());

        SavedSessionTree loaded = repository.loadTree();
        assertEquals(1, loaded.getFolder().getItems().size());
        SessionInfo info = loaded.getFolder().getItems().get(0);
        assertEquals("legacy-host", info.getId());
        assertEquals("Legacy Provider", info.getProvider());
        assertEquals("https://legacy.example", info.getProviderUrl());
        assertNull(info.getProviderId());
        assertEquals(0, new VpsProviderRepository().listProviders().size());
    }

    public void testLoadTreeNormalizesTechnicalEmptyRoot() throws Exception {
        Path configDir = Files.createTempDirectory("vps-ledger-empty-root");
        App.getCONTEXT().setConfigDir(configDir.toFile());

        SessionInfo info = new SessionInfo();
        info.setId("host-1");
        info.setName("alpha");
        info.setHost("192.0.2.50");
        info.setUser("root");

        SessionFolder visibleRoot = new SessionFolder();
        visibleRoot.setId("folder-visible");
        visibleRoot.setName("My sites");
        visibleRoot.getItems().add(info);

        SessionFolder technicalRoot = new SessionFolder();
        technicalRoot.setId("folder-empty-root");
        technicalRoot.setName("Empty_Root");
        technicalRoot.getFolders().add(visibleRoot);

        new VpsHostRepository().saveTree(technicalRoot, info.getId(), false);

        SavedSessionTree loaded = new VpsHostRepository().loadTree();
        assertEquals("My sites", loaded.getFolder().getName());
        assertEquals(1, loaded.getFolder().getItems().size());
        assertEquals("alpha", loaded.getFolder().getItems().get(0).getName());
    }

    public void testHostJsonDoesNotEmbedSshPassword() throws Exception {
        SessionInfo info = new SessionInfo();
        info.setId("host-password");
        info.setName("password-vps");
        info.setHost("192.0.2.12");
        info.setUser("root");
        info.setPassword("ssh-secret");
        info.setProxyPassword("proxy-secret");
        HopEntry hop = new HopEntry();
        hop.setId("hop-1");
        hop.setHost("192.0.2.13");
        hop.setUser("jump");
        hop.setPassword("jump-secret");
        info.getJumpHosts().add(hop);

        String json = new VpsHostRepository().toHostJson(info);

        assertFalse(json.contains("ssh-secret"));
        assertFalse(json.contains("proxy-secret"));
        assertFalse(json.contains("jump-secret"));
        assertFalse(json.contains("\"password\""));
        assertFalse(json.contains("\"proxyPassword\""));
        assertEquals("SSH_PASSWORD", VpsLedgerServices.SSH_PASSWORD_SECRET);
        assertEquals("PROXY_PASSWORD", VpsLedgerServices.PROXY_PASSWORD_SECRET);
        assertEquals("PASSWORD", VpsLedgerServices.JUMP_PASSWORD_SECRET);
    }

    public void testHostJsonCanReadLegacySecretFields() throws Exception {
        String json = "{"
                + "\"id\":\"legacy-host\","
                + "\"name\":\"legacy\","
                + "\"host\":\"192.0.2.14\","
                + "\"user\":\"root\","
                + "\"password\":\"ssh-secret\","
                + "\"proxyPassword\":\"proxy-secret\","
                + "\"jumpHosts\":[{\"id\":\"hop-1\",\"host\":\"192.0.2.15\",\"user\":\"jump\",\"password\":\"jump-secret\"}]"
                + "}";

        SessionInfo info = new VpsHostRepository().fromHostJson(json);

        assertEquals("ssh-secret", info.getPassword());
        assertEquals("proxy-secret", info.getProxyPassword());
        assertEquals("jump-secret", info.getJumpHosts().get(0).getPassword());
    }
}
