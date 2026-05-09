package muon.app.common;

import junit.framework.TestCase;
import muon.app.App;
import muon.app.common.secrets.LegacyPkcs12SecretStore;
import muon.app.common.secrets.SecretAliases;
import muon.app.common.secrets.SecretStoreLockedException;
import muon.app.common.secrets.SecretStore;
import muon.app.common.settings.Settings;
import muon.app.common.settings.SettingsManager;
import muon.app.ui.components.session.SavedSessionTree;
import muon.app.ui.components.session.SessionFolder;
import muon.app.ui.components.session.SessionInfo;
import muon.app.util.Constants;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class PasswordStoreTest extends TestCase {

    @Override
    protected void setUp() {
        PasswordStore.resetForTests();
        PasswordStore.setPrimaryStoreFactoryForTests(FakeSecretStore::new);
    }

    @Override
    protected void tearDown() {
        PasswordStore.resetForTests();
    }

    public void testMigrateLegacySecretsToSystemStoreDisablesLegacyReadsForCurrentRun() throws Exception {
        Path configDir = Files.createTempDirectory("password-store-migrate");
        App.getCONTEXT().setConfigDir(configDir.toFile());
        App.getCONTEXT().setSettingsManager(new SettingsManager(configDir.toFile()));
        App.getCONTEXT().setSettings(new Settings());

        writeLegacyPasswords(configDir);

        PasswordStore store = PasswordStore.getSharedInstance();
        SavedSessionTree tree = createTree("legacy-host");
        assertEquals(2, store.migrateLegacySecretsToSystemStore(tree));

        assertEquals("ssh-secret",
                     store.getSecretWithoutPrompt(SecretAliases.sshPassword("legacy-host")).getValue());
        assertEquals("vikunja-secret",
                     store.getSecretWithoutPrompt(SecretAliases.VIKUNJA_API_TOKEN).getValue());

        store.saveSecretToSystemStore(SecretAliases.sshPassword("legacy-host"), null);
        assertEquals(PasswordStore.SecretReadStatus.MISSING,
                     store.getSecretWithoutPrompt(SecretAliases.sshPassword("legacy-host")).getStatus());
    }

    public void testCompletedMigrationMarkerPreventsLegacyReadsOnNewInstance() throws Exception {
        Path configDir = Files.createTempDirectory("password-store-marker");
        App.getCONTEXT().setConfigDir(configDir.toFile());
        App.getCONTEXT().setSettingsManager(new SettingsManager(configDir.toFile()));
        App.getCONTEXT().setSettings(new Settings());

        writeLegacyPasswords(configDir);
        writeCompletedMigrationMarker(configDir.resolve(Constants.VPS_LEDGER_DB_FILE));

        PasswordStore.resetForTests();
        PasswordStore.setPrimaryStoreFactoryForTests(FakeSecretStore::new);

        PasswordStore store = PasswordStore.getSharedInstance();
        assertEquals(PasswordStore.SecretReadStatus.MISSING,
                     store.getSecretWithoutPrompt(SecretAliases.sshPassword("legacy-host")).getStatus());
        assertEquals(PasswordStore.SecretReadStatus.MISSING,
                     store.getSecretWithoutPrompt(SecretAliases.VIKUNJA_API_TOKEN).getStatus());
    }

    public void testGetSecretWithoutPromptReturnsLockedWhenPrimaryStoreNeedsPrompt() throws Exception {
        Path configDir = Files.createTempDirectory("password-store-primary-locked");
        App.getCONTEXT().setConfigDir(configDir.toFile());
        App.getCONTEXT().setSettingsManager(new SettingsManager(configDir.toFile()));
        App.getCONTEXT().setSettings(new Settings());

        PasswordStore.resetForTests();
        PasswordStore.setPrimaryStoreFactoryForTests(PromptingSecretStore::new);

        PasswordStore store = PasswordStore.getSharedInstance();
        assertEquals(PasswordStore.SecretReadStatus.LOCKED,
                     store.getSecretWithoutPrompt(SecretAliases.INFISICAL_CLIENT_SECRET).getStatus());
    }

    public void testSaveSecretWithoutPromptFallsBackToSessionStoreWhenPrimaryStoreNeedsPrompt() throws Exception {
        Path configDir = Files.createTempDirectory("password-store-save-fallback");
        App.getCONTEXT().setConfigDir(configDir.toFile());
        App.getCONTEXT().setSettingsManager(new SettingsManager(configDir.toFile()));
        App.getCONTEXT().setSettings(new Settings());

        PasswordStore.resetForTests();
        PasswordStore.setPrimaryStoreFactoryForTests(PromptingSecretStore::new);

        PasswordStore store = PasswordStore.getSharedInstance();
        store.saveSecretWithoutPrompt(SecretAliases.INFISICAL_CLIENT_SECRET, "session-secret");

        PasswordStore.SecretReadResult result = store.getSecretWithoutPrompt(SecretAliases.INFISICAL_CLIENT_SECRET);
        assertEquals(PasswordStore.SecretReadStatus.FOUND, result.getStatus());
        assertEquals("session-secret", result.getValue());
    }

    private void writeLegacyPasswords(Path configDir) throws Exception {
        LegacyPkcs12SecretStore legacyStore = new LegacyPkcs12SecretStore(configDir.toFile());
        legacyStore.unlock(new char[0]);
        legacyStore.set("legacy-host", "ssh-secret".toCharArray());
        legacyStore.set(SecretAliases.LEGACY_VIKUNJA_API_TOKEN, "vikunja-secret".toCharArray());
    }

    private void writeCompletedMigrationMarker(Path dbPath) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE app_state (key TEXT PRIMARY KEY, value TEXT)");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement("INSERT INTO app_state(key, value) VALUES (?, ?)")) {
            statement.setString(1, Constants.MIGRATION_4_0_0_COMPLETED_AT_KEY);
            statement.setString(2, "1");
            statement.executeUpdate();
        }
    }

    private SavedSessionTree createTree(String hostId) {
        SessionInfo info = new SessionInfo();
        info.setId(hostId);
        info.setName("legacy");
        info.setHost("192.0.2.10");
        info.setUser("root");

        SessionFolder folder = new SessionFolder();
        folder.setId("root-folder");
        folder.setName("My sites");
        folder.getItems().add(info);

        SavedSessionTree tree = new SavedSessionTree();
        tree.setFolder(folder);
        tree.setLastSelection(hostId);
        return tree;
    }

    private static class FakeSecretStore implements SecretStore {
        private final Map<String, char[]> secrets = new HashMap<>();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String backendName() {
            return "Fake Secret Service";
        }

        @Override
        public char[] get(String alias) {
            char[] value = secrets.get(alias);
            return value == null ? null : Arrays.copyOf(value, value.length);
        }

        @Override
        public void set(String alias, char[] secret) {
            delete(alias);
            if (secret != null && secret.length > 0) {
                secrets.put(alias, Arrays.copyOf(secret, secret.length));
            }
        }

        @Override
        public void delete(String alias) {
            char[] existing = secrets.remove(alias);
            if (existing != null) {
                Arrays.fill(existing, '\0');
            }
        }
    }

    private static final class PromptingSecretStore extends FakeSecretStore {

        @Override
        public char[] getWithoutPrompt(String alias) throws Exception {
            throw new SecretStoreLockedException("prompt required");
        }

        @Override
        public void setWithoutPrompt(String alias, char[] secret) throws Exception {
            throw new SecretStoreLockedException("prompt required");
        }

        @Override
        public void deleteWithoutPrompt(String alias) throws Exception {
            throw new SecretStoreLockedException("prompt required");
        }
    }
}
