package muon.app.vps;

import junit.framework.TestCase;
import muon.app.App;
import muon.app.common.PasswordStore;
import muon.app.common.secrets.LegacyPkcs12SecretStore;
import muon.app.common.secrets.SecretAliases;
import muon.app.common.secrets.SecretStore;
import muon.app.common.settings.Settings;
import muon.app.common.settings.SettingsManager;
import muon.app.ui.components.session.SavedSessionTree;
import muon.app.ui.components.session.SessionInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class ProfileMigrationCoordinatorTest extends TestCase {

    @Override
    protected void setUp() {
        PasswordStore.resetForTests();
        PasswordStore.setPrimaryStoreFactoryForTests(FakeSecretStore::new);
    }

    @Override
    protected void tearDown() {
        PasswordStore.resetForTests();
    }

    public void testRunIfNeededArchivesExistingDbRebuildsLegacyJsonAndMarksCompletion() throws Exception {
        Path configDir = Files.createTempDirectory("migration-coordinator-success");
        App.getCONTEXT().setConfigDir(configDir.toFile());
        App.getCONTEXT().setSettingsManager(new SettingsManager(configDir.toFile()));
        App.getCONTEXT().setSettings(new Settings());

        writeLegacySessionStore(configDir);
        writeLegacyPasswords(configDir);
        Files.writeString(configDir.resolve("vps-ledger.db"), "legacy-db");

        AtomicBoolean pushed = new AtomicBoolean(false);
        AtomicLong pushedUpdatedAt = new AtomicLong(0L);
        ProfileMigrationCoordinator coordinator = new ProfileMigrationCoordinator(
                new VpsHostRepository(),
                new ProfileMigrationCoordinator.MigrationPrompt() {
                    @Override
                    public ProfileMigrationCoordinator.MigrationInput prompt(ProfileMigrationCoordinator.MigrationInput defaults) {
                        return buildInput(defaults, "client-secret");
                    }

                    @Override
                    public void showError(String title, String message) {
                        fail("Unexpected migration error: " + title + " - " + message);
                    }
                },
                localUpdatedAt -> {
                    pushed.set(true);
                    pushedUpdatedAt.set(localUpdatedAt);
                    SavedSessionTree tree = new VpsHostRepository().loadTree();
                    assertEquals(1, tree.getFolder().getItems().size());
                    assertEquals("legacy-host", tree.getFolder().getItems().get(0).getId());
                    assertEquals(0, new VpsProviderRepository().listProviders().size());
                    assertEquals("ssh-secret",
                                 PasswordStore.getSharedInstance().getSecretWithoutPrompt(SecretAliases.sshPassword("legacy-host")).getValue());
                });

        assertTrue(coordinator.runIfNeeded());
        assertTrue(pushed.get());

        SavedSessionTree loaded = new VpsHostRepository().loadTree();
        assertEquals(1, loaded.getFolder().getItems().size());
        SessionInfo info = loaded.getFolder().getItems().get(0);
        assertEquals("legacy-host", info.getId());
        assertEquals("Legacy Provider", info.getProvider());
        assertNull(info.getProviderId());
        assertEquals(0, new VpsProviderRepository().listProviders().size());

        VpsHostRepository repository = new VpsHostRepository();
        assertEquals(configDir.resolve("session-store.json").toString(),
                     repository.getAppStateValue(ProfileMigrationCoordinator.SOURCE_KEY));
        assertEquals(String.valueOf(pushedUpdatedAt.get()),
                     repository.getAppStateValue(InfisicalSyncService.LOCAL_STATE_UPDATED_AT_KEY));
        assertNotNull(repository.getAppStateValue(ProfileMigrationCoordinator.COMPLETED_AT_KEY));

        String archivedDb = repository.getAppStateValue(ProfileMigrationCoordinator.ARCHIVED_DB_KEY);
        assertNotNull(archivedDb);
        assertFalse(archivedDb.isBlank());
        assertTrue(Files.exists(Path.of(archivedDb)));
        assertEquals("legacy-db", Files.readString(Path.of(archivedDb)));

        String archivedPasswordStore = repository.getAppStateValue(ProfileMigrationCoordinator.ARCHIVED_PASSWORD_STORE_KEY);
        assertNotNull(archivedPasswordStore);
        assertFalse(archivedPasswordStore.isBlank());
        assertTrue(Files.exists(Path.of(archivedPasswordStore)));
        assertFalse(Files.exists(configDir.resolve("passwords.pfx")));

        assertEquals("https://infisical.example", App.getGlobalSettings().getInfisicalBaseUrl());
        assertEquals("project-123", App.getGlobalSettings().getInfisicalProjectId());
        assertEquals("prod", App.getGlobalSettings().getInfisicalEnvironment());
        assertEquals("/vps-manager", App.getGlobalSettings().getInfisicalSecretBasePath());
        assertEquals("client-123", App.getGlobalSettings().getInfisicalClientId());
        assertEquals("org-slug", App.getGlobalSettings().getInfisicalOrganizationSlug());
        assertEquals("client-secret",
                     PasswordStore.getSharedInstance().getSecretWithoutPrompt(InfisicalClient.CLIENT_SECRET_ALIAS).getValue());
        assertEquals("ssh-secret",
                     PasswordStore.getSharedInstance().getSecretWithoutPrompt(SecretAliases.sshPassword("legacy-host")).getValue());
        assertEquals("vikunja-secret",
                     PasswordStore.getSharedInstance().getSecretWithoutPrompt(SecretAliases.VIKUNJA_API_TOKEN).getValue());
    }

    public void testRunIfNeededStopsWhenWizardIsCancelled() throws Exception {
        Path configDir = Files.createTempDirectory("migration-coordinator-cancel");
        App.getCONTEXT().setConfigDir(configDir.toFile());
        App.getCONTEXT().setSettingsManager(new SettingsManager(configDir.toFile()));
        App.getCONTEXT().setSettings(new Settings());

        writeLegacySessionStore(configDir);
        Files.writeString(configDir.resolve("vps-ledger.db"), "legacy-db");

        AtomicBoolean pushed = new AtomicBoolean(false);
        ProfileMigrationCoordinator coordinator = new ProfileMigrationCoordinator(
                new VpsHostRepository(),
                new ProfileMigrationCoordinator.MigrationPrompt() {
                    @Override
                    public ProfileMigrationCoordinator.MigrationInput prompt(ProfileMigrationCoordinator.MigrationInput defaults) {
                        return null;
                    }

                    @Override
                    public void showError(String title, String message) {
                        fail("Unexpected migration error: " + title + " - " + message);
                    }
                },
                localUpdatedAt -> pushed.set(true));

        assertFalse(coordinator.runIfNeeded());
        assertFalse(pushed.get());
        assertTrue(Files.exists(configDir.resolve("vps-ledger.db")));
        assertEquals("legacy-db", Files.readString(configDir.resolve("vps-ledger.db")));
        assertFalse(Files.exists(configDir.resolve("passwords.pfx")));
    }

    public void testRunIfNeededSkipsWhenCompletionMarkerAlreadyExists() throws Exception {
        Path configDir = Files.createTempDirectory("migration-coordinator-skip");
        App.getCONTEXT().setConfigDir(configDir.toFile());
        App.getCONTEXT().setSettingsManager(new SettingsManager(configDir.toFile()));
        App.getCONTEXT().setSettings(new Settings());

        writeLegacySessionStore(configDir);
        Path dbPath = configDir.resolve("vps-ledger.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE app_state (key TEXT PRIMARY KEY, value TEXT)");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement("INSERT INTO app_state(key, value) VALUES (?, ?)")) {
            statement.setString(1, ProfileMigrationCoordinator.COMPLETED_AT_KEY);
            statement.setString(2, "1");
            statement.executeUpdate();
        }

        AtomicBoolean prompted = new AtomicBoolean(false);
        AtomicBoolean pushed = new AtomicBoolean(false);
        ProfileMigrationCoordinator coordinator = new ProfileMigrationCoordinator(
                new VpsHostRepository(),
                new ProfileMigrationCoordinator.MigrationPrompt() {
                    @Override
                    public ProfileMigrationCoordinator.MigrationInput prompt(ProfileMigrationCoordinator.MigrationInput defaults) {
                        prompted.set(true);
                        return buildInput(defaults, "client-secret");
                    }

                    @Override
                    public void showError(String title, String message) {
                        fail("Unexpected migration error: " + title + " - " + message);
                    }
                },
                localUpdatedAt -> pushed.set(true));

        assertTrue(coordinator.runIfNeeded());
        assertFalse(prompted.get());
        assertFalse(pushed.get());
    }

    public void testRunIfNeededFailsWhenSystemStoreIsUnavailable() throws Exception {
        PasswordStore.resetForTests();
        PasswordStore.setPrimaryStoreFactoryForTests(() -> null);

        Path configDir = Files.createTempDirectory("migration-coordinator-no-store");
        App.getCONTEXT().setConfigDir(configDir.toFile());
        App.getCONTEXT().setSettingsManager(new SettingsManager(configDir.toFile()));
        App.getCONTEXT().setSettings(new Settings());

        writeLegacySessionStore(configDir);
        writeLegacyPasswords(configDir);

        AtomicReference<String> shownMessage = new AtomicReference<>();
        ProfileMigrationCoordinator coordinator = new ProfileMigrationCoordinator(
                new VpsHostRepository(),
                new ProfileMigrationCoordinator.MigrationPrompt() {
                    @Override
                    public ProfileMigrationCoordinator.MigrationInput prompt(ProfileMigrationCoordinator.MigrationInput defaults) {
                        return buildInput(defaults, "client-secret");
                    }

                    @Override
                    public void showError(String title, String message) {
                        shownMessage.set(message);
                    }
                },
                localUpdatedAt -> fail("Initial push should not run when the system store is unavailable"));

        assertFalse(coordinator.runIfNeeded());
        assertNotNull(shownMessage.get());
        assertTrue(shownMessage.get().contains("Secret Service is not available"));
    }

    public void testInitialInfisicalPushFailureDoesNotStopMigration() throws Exception {
        Path configDir = Files.createTempDirectory("migration-coordinator-push-failure");
        App.getCONTEXT().setConfigDir(configDir.toFile());
        App.getCONTEXT().setSettingsManager(new SettingsManager(configDir.toFile()));
        App.getCONTEXT().setSettings(new Settings());

        writeLegacySessionStore(configDir);
        writeLegacyPasswords(configDir);

        AtomicReference<String> shownMessage = new AtomicReference<>();
        AtomicBoolean pushAttempted = new AtomicBoolean(false);
        ProfileMigrationCoordinator coordinator = new ProfileMigrationCoordinator(
                new VpsHostRepository(),
                new ProfileMigrationCoordinator.MigrationPrompt() {
                    @Override
                    public ProfileMigrationCoordinator.MigrationInput prompt(ProfileMigrationCoordinator.MigrationInput defaults) {
                        return buildInput(defaults, "client-secret");
                    }

                    @Override
                    public void showError(String title, String message) {
                        shownMessage.set(message);
                    }
                },
                localUpdatedAt -> {
                    pushAttempted.set(true);
                    throw new IllegalStateException();
                });

        assertTrue(coordinator.runIfNeeded());
        assertTrue(pushAttempted.get());
        assertNull(shownMessage.get());

        VpsHostRepository repository = new VpsHostRepository();
        assertNotNull(repository.getAppStateValue(ProfileMigrationCoordinator.COMPLETED_AT_KEY));
        assertNotNull(repository.getAppStateValue(InfisicalSyncService.LOCAL_STATE_UPDATED_AT_KEY));
    }

    private ProfileMigrationCoordinator.MigrationInput buildInput(ProfileMigrationCoordinator.MigrationInput defaults,
                                                                  String clientSecret) {
        ProfileMigrationCoordinator.MigrationInput input = defaults == null
                ? new ProfileMigrationCoordinator.MigrationInput()
                : defaults.copy();
        input.infisicalBaseUrl = "https://infisical.example";
        input.infisicalProjectId = "project-123";
        input.infisicalEnvironment = "prod";
        input.infisicalSecretBasePath = "/vps-manager";
        input.infisicalClientId = "client-123";
        input.infisicalOrganizationSlug = "org-slug";
        input.infisicalClientSecret = clientSecret;
        return input;
    }

    private void writeLegacyPasswords(Path configDir) throws Exception {
        LegacyPkcs12SecretStore legacyStore = new LegacyPkcs12SecretStore(configDir.toFile());
        legacyStore.unlock(new char[0]);
        legacyStore.set("legacy-host", "ssh-secret".toCharArray());
        legacyStore.set(SecretAliases.LEGACY_VIKUNJA_API_TOKEN, "vikunja-secret".toCharArray());
    }

    private void writeLegacySessionStore(Path configDir) throws Exception {
        String json = "{"
                + "\"folder\":{"
                + "\"id\":\"root-folder\","
                + "\"name\":\"My sites\","
                + "\"folders\":[],"
                + "\"items\":[{"
                + "\"id\":\"legacy-host\","
                + "\"name\":\"legacy-vps\","
                + "\"host\":\"192.0.2.10\","
                + "\"user\":\"root\","
                + "\"provider\":\"Legacy Provider\","
                + "\"providerUrl\":\"https://legacy.example\","
                + "\"privateKeyFile\":\"/home/userk/.ssh/legacy-key\","
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
        Files.writeString(configDir.resolve("session-store.json"), json);
    }

    private static final class FakeSecretStore implements SecretStore {
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
}
