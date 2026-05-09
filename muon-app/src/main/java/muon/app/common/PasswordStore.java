package muon.app.common;

import lombok.extern.slf4j.Slf4j;
import muon.app.App;
import muon.app.common.secrets.LegacyPkcs12SecretStore;
import muon.app.common.secrets.LinuxSecretServiceStore;
import muon.app.common.secrets.SecretAliases;
import muon.app.common.secrets.SecretStoreLockedException;
import muon.app.common.secrets.SecretStore;
import muon.app.common.secrets.SessionSecretStore;
import muon.app.common.secrets.WindowsCredentialStore;
import muon.app.ui.components.session.HopEntry;
import muon.app.ui.components.session.SavedSessionTree;
import muon.app.ui.components.session.SessionFolder;
import muon.app.ui.components.session.SessionInfo;
import muon.app.util.Constants;
import muon.app.util.OptionPaneUtils;

import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static muon.app.util.PlatformUtils.IS_LINUX;
import static muon.app.util.PlatformUtils.IS_WINDOWS;

@Slf4j
public final class PasswordStore {

    private static final DateTimeFormatter LEGACY_BACKUP_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public enum SecretReadStatus {
        FOUND,
        MISSING,
        LOCKED
    }

    public static final class SecretReadResult {
        private final String value;
        private final SecretReadStatus status;

        public SecretReadResult(String value, SecretReadStatus status) {
            this.value = value;
            this.status = status;
        }

        public String getValue() {
            return value;
        }

        public SecretReadStatus getStatus() {
            return status;
        }
    }

    public interface FallbackDecisionProvider {
        boolean shouldUseLegacyFallback(String backendName);
    }

    @FunctionalInterface
    public interface PrimaryStoreFactory {
        SecretStore create() throws Exception;
    }

    private static PasswordStore instance;
    private static PrimaryStoreFactory primaryStoreFactory = PasswordStore::createPlatformPrimaryStore;

    private final File configDir;
    private final LegacyPkcs12SecretStore legacyStore;
    private final SessionSecretStore sessionStore = new SessionSecretStore();
    private FallbackDecisionProvider fallbackDecisionProvider = this::askForLegacyFallback;
    private SecretStore primaryStore;
    private SecretStore writeFallbackStore;
    private boolean primaryStoreChecked;
    private boolean migrationAttempted;
    private boolean migrationDeletePromptShown;
    private boolean plaintextScrubNeeded;
    private Boolean legacyAccessEnabled;

    private PasswordStore(File configDir) throws Exception {
        this.configDir = configDir;
        this.legacyStore = new LegacyPkcs12SecretStore(configDir);
    }

    public static synchronized PasswordStore getSharedInstance() throws Exception {
        File currentConfigDir = App.getCONTEXT().getConfigDir();
        if (instance == null || !instance.configDir.equals(currentConfigDir)) {
            instance = new PasswordStore(currentConfigDir);
        }
        return instance;
    }

    public static synchronized void setPrimaryStoreFactoryForTests(PrimaryStoreFactory factory) {
        primaryStoreFactory = factory == null ? PasswordStore::createPlatformPrimaryStore : factory;
        if (instance != null) {
            instance.primaryStore = null;
            instance.writeFallbackStore = null;
            instance.primaryStoreChecked = false;
        }
    }

    public static synchronized void resetForTests() {
        primaryStoreFactory = PasswordStore::createPlatformPrimaryStore;
        instance = null;
    }

    public synchronized void setFallbackDecisionProvider(FallbackDecisionProvider fallbackDecisionProvider) {
        this.fallbackDecisionProvider = fallbackDecisionProvider == null ? this::askForLegacyFallback : fallbackDecisionProvider;
        this.writeFallbackStore = null;
    }

    public boolean isUnlocked() {
        return getPrimaryStore() != null || legacyStore.isUnlocked() || !sessionStore.isPersistent();
    }

    public synchronized boolean isSystemStoreAvailable() {
        return getPrimaryStore() != null;
    }

    public synchronized String backendName() {
        SecretStore primary = getPrimaryStore();
        if (primary != null) {
            return primary.backendName();
        }
        if (writeFallbackStore != null) {
            return writeFallbackStore.backendName();
        }
        return expectedSystemBackendName();
    }

    public synchronized String expectedSystemStoreBackendName() {
        return expectedSystemBackendName();
    }

    public synchronized void unlockStore(char[] password) throws Exception {
        legacyStore.unlock(password);
    }

    public synchronized String getSecret(String alias) {
        char[] secret = getSecretChars(alias);
        if (secret == null) {
            return null;
        }
        try {
            return new String(secret);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    public synchronized SecretReadResult getSecretWithoutPrompt(String alias) {
        SecretStore primary = getPrimaryStore();
        boolean primaryLocked = false;
        if (primary != null) {
            try {
                char[] value = primary.getWithoutPrompt(alias);
                if (value != null) {
                    return toSecretReadResult(value, SecretReadStatus.FOUND);
                }
            } catch (SecretStoreLockedException e) {
                primaryLocked = true;
                log.info("{} is locked while reading {} without prompting", primary.backendName(), alias);
            } catch (Exception e) {
                log.error("Unable to read secret {} from {}", alias, primary.backendName(), e);
            }
        }

        char[] sessionValue = sessionStore.get(alias);
        if (sessionValue != null) {
            return toSecretReadResult(sessionValue, SecretReadStatus.FOUND);
        }

        if (isLegacyAccessEnabled() && legacyStore.isUnlocked()) {
            try {
                char[] value = legacyStore.get(alias);
                if (value != null) {
                    return toSecretReadResult(value, SecretReadStatus.FOUND);
                }
                String legacyAlias = SecretAliases.legacyAliasFor(alias);
                if (legacyAlias != null) {
                    value = legacyStore.get(legacyAlias);
                    if (value != null) {
                        return toSecretReadResult(value, SecretReadStatus.FOUND);
                    }
                }
            } catch (Exception e) {
                log.error("Unable to read secret {} from legacy store", alias, e);
            }
        } else if (isLegacyAccessEnabled() && legacyStore.exists()) {
            return new SecretReadResult(null, SecretReadStatus.LOCKED);
        }

        if (primaryLocked) {
            return new SecretReadResult(null, SecretReadStatus.LOCKED);
        }

        return new SecretReadResult(null, SecretReadStatus.MISSING);
    }

    public synchronized void saveSecret(String alias, String value) throws Exception {
        SecretStore store = selectWriteStore();
        saveSecret(store, alias, value);
    }

    public synchronized void saveSecretWithoutPrompt(String alias, String value) throws Exception {
        SecretStore primary = getPrimaryStore();
        if (primary != null) {
            try {
                saveSecret(primary, alias, value, false);
                return;
            } catch (SecretStoreLockedException e) {
                log.info("{} is locked while saving {} without prompting; using non-interactive fallback",
                         primary.backendName(),
                         alias);
                saveSecret(selectNonInteractiveFallbackStore(), alias, value, false);
                return;
            } catch (Exception e) {
                log.warn("Unable to persist secret {} to {} without prompting; using non-interactive fallback",
                         alias,
                         primary.backendName(),
                         e);
                saveSecret(selectNonInteractiveFallbackStore(), alias, value, false);
                return;
            }
        }
        SecretStore store = selectWriteStore(false);
        saveSecret(store, alias, value, false);
    }

    public synchronized void saveSecretToSystemStore(String alias, String value) throws Exception {
        SecretStore primary = requirePrimaryStore();
        saveSecret(primary, alias, value);
        validatePrimarySecret(primary, alias, value);
    }

    public synchronized void populatePassword(SavedSessionTree savedSessionTree) {
        migrateLegacyIfNeeded(savedSessionTree);
        if (savedSessionTree != null) {
            populatePassword(savedSessionTree.getFolder());
        }
    }

    public synchronized boolean consumePlaintextScrubNeeded() {
        boolean value = plaintextScrubNeeded;
        plaintextScrubNeeded = false;
        return value;
    }

    public synchronized void savePasswords(SavedSessionTree savedSessionTree) {
        if (savedSessionTree == null || savedSessionTree.getFolder() == null) {
            return;
        }
        try {
            SecretStore store = selectWriteStore();
            savePassword(savedSessionTree.getFolder(), store);
        } catch (Exception e) {
            log.error("Unable to save passwords", e);
        }
    }

    public boolean unlockUsingMasterPassword() {
        int tries = 0;
        while (tries < 3) {
            try {
                JPasswordField txtPass = new JPasswordField(30);
                if (OptionPaneUtils.showOptionDialog(App.getAppWindow(),
                                                     new Object[]{App.getCONTEXT().getBundle().getString("master_password"), txtPass},
                                                     App.getCONTEXT().getBundle().getString("master_password")) == JOptionPane.OK_OPTION) {
                    unlockStore(txtPass.getPassword());
                    return true;
                }
                return false;
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                JOptionPane.showMessageDialog(App.getAppWindow(),
                                              App.getCONTEXT().getBundle().getString("error_loading_password"),
                                              App.getCONTEXT().getBundle().getString("error"),
                                              JOptionPane.ERROR_MESSAGE);
                tries++;
            }
        }
        return false;
    }

    public synchronized boolean changeStorePassword(char[] newPassword) throws Exception {
        if (!isLegacyAccessEnabled()) {
            return false;
        }
        if (!ensureLegacyUnlocked()) {
            return false;
        }
        return legacyStore.changePassword(newPassword);
    }

    public synchronized boolean unlockLegacySecretsIfNeeded() {
        if (!isLegacyAccessEnabled()) {
            return true;
        }
        if (!legacyStore.exists() || legacyStore.isUnlocked()) {
            return true;
        }
        return ensureLegacyUnlocked();
    }

    public synchronized int migrateLegacySecretsToSystemStore(SavedSessionTree savedSessionTree) throws Exception {
        SecretStore primary = requirePrimaryStore();
        if (!legacyStore.exists()) {
            disableLegacyAccessForCurrentProfile();
            return 0;
        }
        if (!ensureLegacyUnlocked()) {
            throw new IllegalStateException("Unable to unlock passwords.pfx");
        }

        Map<String, char[]> secretsToMigrate = collectLegacySecretsForSystemStore(savedSessionTree);
        for (Map.Entry<String, char[]> entry : secretsToMigrate.entrySet()) {
            primary.set(entry.getKey(), entry.getValue());
        }
        validatePrimarySecrets(primary, secretsToMigrate);
        disableLegacyAccessForCurrentProfile();
        return secretsToMigrate.size();
    }

    public synchronized void disableLegacyAccessForCurrentProfile() {
        legacyAccessEnabled = false;
        if (writeFallbackStore == legacyStore) {
            writeFallbackStore = null;
        }
    }

    public synchronized String backupLegacyStoreIfPresent() throws Exception {
        Path source = legacyStore.getStoreFile().toPath();
        if (!Files.exists(source)) {
            return null;
        }
        String suffix = LEGACY_BACKUP_SUFFIX.format(LocalDateTime.now());
        Path archived = source.resolveSibling("passwords.pre-" + Constants.APPLICATION_VERSION + "-" + suffix + ".pfx");
        Files.move(source, archived, StandardCopyOption.REPLACE_EXISTING);
        return archived.toString();
    }

    private void populatePassword(SessionFolder folder) {
        if (folder == null) {
            return;
        }
        for (SessionInfo info : folder.getItems()) {
            populatePassword(info);
        }
        for (SessionFolder f : folder.getFolders()) {
            populatePassword(f);
        }
    }

    private void populatePassword(SessionInfo info) {
        if (info == null || info.getId() == null || info.getId().isBlank()) {
            return;
        }

        String inlineSshPassword = info.getPassword();
        String sshPassword = getSecretString(SecretAliases.sshPassword(info.getId()));
        if (hasSecretValue(sshPassword)) {
            info.setPassword(sshPassword);
        } else if (hasSecretValue(inlineSshPassword)) {
            migratePlaintextSecret(SecretAliases.sshPassword(info.getId()), inlineSshPassword);
        }

        String inlineProxyPassword = info.getProxyPassword();
        String proxyPassword = getSecretString(SecretAliases.proxyPassword(info.getId()));
        if (hasSecretValue(proxyPassword)) {
            info.setProxyPassword(proxyPassword);
        } else if (hasSecretValue(inlineProxyPassword)) {
            migratePlaintextSecret(SecretAliases.proxyPassword(info.getId()), inlineProxyPassword);
        }

        for (HopEntry hop : info.getJumpHosts()) {
            if (hop.getId() == null || hop.getId().isBlank()) {
                hop.setId(UUID.randomUUID().toString());
                plaintextScrubNeeded = true;
            }
            String inlineJumpPassword = hop.getPassword();
            String jumpPassword = getSecretString(SecretAliases.jumpPassword(info.getId(), hop.getId()));
            if (hasSecretValue(jumpPassword)) {
                hop.setPassword(jumpPassword);
            } else if (hasSecretValue(inlineJumpPassword)) {
                migratePlaintextSecret(SecretAliases.jumpPassword(info.getId(), hop.getId()), inlineJumpPassword);
            }
        }
    }

    private void migratePlaintextSecret(String alias, String value) {
        try {
            saveSecret(alias, value);
            plaintextScrubNeeded = true;
        } catch (Exception e) {
            log.error("Unable to migrate plaintext secret {}", alias, e);
        }
    }

    private void savePassword(SessionFolder folder, SecretStore store) throws Exception {
        for (SessionInfo info : folder.getItems()) {
            if (info.getId() == null || info.getId().isBlank()) {
                info.setId(UUID.randomUUID().toString());
            }
            saveSecret(store, SecretAliases.sshPassword(info.getId()), info.getPassword());
            saveSecret(store, SecretAliases.proxyPassword(info.getId()), info.getProxyPassword());
            for (HopEntry hop : info.getJumpHosts()) {
                if (hop.getId() == null || hop.getId().isBlank()) {
                    hop.setId(UUID.randomUUID().toString());
                }
                saveSecret(store, SecretAliases.jumpPassword(info.getId(), hop.getId()), hop.getPassword());
            }
        }
        for (SessionFolder f : folder.getFolders()) {
            savePassword(f, store);
        }
    }

    private void saveSecret(SecretStore store, String alias, String value) throws Exception {
        saveSecret(store, alias, value, true);
    }

    private void saveSecret(SecretStore store, String alias, String value, boolean allowPrompt) throws Exception {
        if (!hasSecretValue(value)) {
            deleteFromKnownStores(alias, allowPrompt);
            return;
        }
        char[] secret = value.toCharArray();
        try {
            if (allowPrompt) {
                store.set(alias, secret);
            } else {
                store.setWithoutPrompt(alias, secret);
            }
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private void deleteFromKnownStores(String alias) {
        deleteFromKnownStores(alias, true);
    }

    private void deleteFromKnownStores(String alias, boolean allowPrompt) {
        deleteAliasFromKnownStores(alias, allowPrompt);
        String legacyAlias = SecretAliases.legacyAliasFor(alias);
        if (legacyAlias != null) {
            deleteAliasFromKnownStores(legacyAlias, allowPrompt);
        }
    }

    private void deleteAliasFromKnownStores(String alias, boolean allowPrompt) {
        List<SecretStore> stores = List.of(sessionStore);
        for (SecretStore store : stores) {
            try {
                if (allowPrompt) {
                    store.delete(alias);
                } else {
                    store.deleteWithoutPrompt(alias);
                }
            } catch (Exception e) {
                log.debug("Unable to delete {} from {}", alias, store.backendName(), e);
            }
        }
        SecretStore primary = getPrimaryStore();
        if (primary != null) {
            try {
                if (allowPrompt) {
                    primary.delete(alias);
                } else {
                    primary.deleteWithoutPrompt(alias);
                }
            } catch (Exception e) {
                log.debug("Unable to delete {} from {}", alias, primary.backendName(), e);
            }
        }
        if (isLegacyAccessEnabled() && legacyStore.isUnlocked()) {
            try {
                if (allowPrompt) {
                    legacyStore.delete(alias);
                } else {
                    legacyStore.deleteWithoutPrompt(alias);
                }
            } catch (Exception e) {
                log.debug("Unable to delete {} from legacy store", alias, e);
            }
        }
    }

    private String getSecretString(String alias) {
        char[] chars = getSecretChars(alias);
        if (chars == null) {
            return null;
        }
        try {
            return new String(chars);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    private char[] getSecretChars(String alias) {
        SecretStore primary = getPrimaryStore();
        if (primary != null) {
            try {
                char[] value = primary.get(alias);
                if (value != null) {
                    return value;
                }
                String legacyAlias = SecretAliases.legacyAliasFor(alias);
                if (isLegacyAccessEnabled() && legacyAlias != null && ensureLegacyUnlockedIfExists()) {
                    value = legacyStore.get(legacyAlias);
                    if (value != null) {
                        primary.set(alias, value);
                        return value;
                    }
                }
            } catch (Exception e) {
                log.error("Unable to read secret {} from {}", alias, primary.backendName(), e);
            }
        }

        char[] sessionValue = sessionStore.get(alias);
        if (sessionValue != null) {
            return sessionValue;
        }

        if (isLegacyAccessEnabled() && ensureLegacyUnlockedIfExists()) {
            try {
                char[] value = legacyStore.get(alias);
                if (value != null) {
                    return value;
                }
                String legacyAlias = SecretAliases.legacyAliasFor(alias);
                return legacyAlias == null ? null : legacyStore.get(legacyAlias);
            } catch (Exception e) {
                log.error("Unable to read secret {} from legacy store", alias, e);
            }
        }
        return null;
    }

    private void migrateLegacyIfNeeded(SavedSessionTree savedSessionTree) {
        if (migrationAttempted) {
            return;
        }
        migrationAttempted = true;

        if (!isLegacyAccessEnabled()) {
            return;
        }
        SecretStore primary = getPrimaryStore();
        if (primary == null || !legacyStore.exists() || !ensureLegacyUnlockedIfExists()) {
            return;
        }

        int copied = 0;
        try {
            Map<String, char[]> legacySecrets = legacyStore.snapshot();
            copied += copyLegacySecret(primary, legacySecrets, SecretAliases.LEGACY_VIKUNJA_API_TOKEN, SecretAliases.VIKUNJA_API_TOKEN);
            copied += copyLegacySecret(primary, legacySecrets, SecretAliases.LEGACY_INFISICAL_CLIENT_SECRET, SecretAliases.INFISICAL_CLIENT_SECRET);
            for (Map.Entry<String, char[]> entry : legacySecrets.entrySet()) {
                if (SecretAliases.isCanonicalAlias(entry.getKey())) {
                    copied += copyIfMissing(primary, entry.getKey(), entry.getValue());
                }
            }
            if (savedSessionTree != null) {
                copied += migrateSessionLegacyAliases(primary, legacySecrets, savedSessionTree.getFolder());
            }
            if (copied > 0) {
                promptBackupLegacyStore();
            }
        } catch (Exception e) {
            log.error("Unable to migrate legacy password store", e);
        }
    }

    private int migrateSessionLegacyAliases(SecretStore primary, Map<String, char[]> legacySecrets, SessionFolder folder) throws Exception {
        if (folder == null) {
            return 0;
        }
        int copied = 0;
        for (SessionInfo info : folder.getItems()) {
            if (info.getId() != null) {
                copied += copyLegacySecret(primary, legacySecrets, info.getId(), SecretAliases.sshPassword(info.getId()));
            }
        }
        for (SessionFolder child : folder.getFolders()) {
            copied += migrateSessionLegacyAliases(primary, legacySecrets, child);
        }
        return copied;
    }

    private int copyLegacySecret(SecretStore primary, Map<String, char[]> legacySecrets,
                                 String legacyAlias, String canonicalAlias) throws Exception {
        char[] value = legacySecrets.get(legacyAlias);
        if (value == null) {
            return 0;
        }
        return copyIfMissing(primary, canonicalAlias, value);
    }

    private int copyIfMissing(SecretStore primary, String alias, char[] value) throws Exception {
        if (primary.get(alias) != null) {
            return 0;
        }
        primary.set(alias, value);
        return 1;
    }

    private void promptBackupLegacyStore() {
        if (migrationDeletePromptShown || GraphicsEnvironment.isHeadless()) {
            return;
        }
        migrationDeletePromptShown = true;
        SwingUtilities.invokeLater(() -> {
            int result = JOptionPane.showConfirmDialog(
                    App.getAppWindow(),
                    "Secrets were migrated to the system credential store. Move the old passwords.pfx file to a backup file?",
                    "MuonSSH",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (result == JOptionPane.YES_OPTION) {
                try {
                    backupLegacyStoreIfPresent();
                } catch (Exception e) {
                    log.error("Unable to back up legacy password store", e);
                }
            }
        });
    }

    private SecretStore selectWriteStore() throws Exception {
        return selectWriteStore(true);
    }

    private SecretStore selectWriteStore(boolean allowPrompt) throws Exception {
        SecretStore primary = getPrimaryStore();
        if (primary != null) {
            return primary;
        }
        if (writeFallbackStore == legacyStore && !isLegacyAccessEnabled()) {
            writeFallbackStore = null;
        }
        if (writeFallbackStore != null) {
            return writeFallbackStore;
        }
        if (!allowPrompt) {
            return selectNonInteractiveFallbackStore();
        }
        if (isLegacyAccessEnabled() && fallbackDecisionProvider.shouldUseLegacyFallback(expectedSystemBackendName())) {
            if (!ensureLegacyUnlocked()) {
                throw new IllegalStateException("Unable to unlock legacy password store");
            }
            writeFallbackStore = legacyStore;
        } else {
            writeFallbackStore = sessionStore;
        }
        return writeFallbackStore;
    }

    private SecretStore selectNonInteractiveFallbackStore() {
        if (isLegacyAccessEnabled() && legacyStore.isUnlocked()) {
            return legacyStore;
        }
        return sessionStore;
    }

    private SecretStore getPrimaryStore() {
        if (primaryStoreChecked && primaryStore != null) {
            return primaryStore;
        }
        primaryStoreChecked = true;
        SecretStore candidate = createPrimaryStore();
        if (candidate != null && candidate.isAvailable()) {
            primaryStore = candidate;
            return primaryStore;
        }
        primaryStore = null;
        return null;
    }

    private SecretStore createPrimaryStore() {
        try {
            return primaryStoreFactory.create();
        } catch (Throwable t) {
            log.warn("Unable to initialize system credential store: {}", t.getMessage());
        }
        return null;
    }

    private boolean ensureLegacyUnlockedIfExists() {
        if (!isLegacyAccessEnabled()) {
            return false;
        }
        if (!legacyStore.exists() && !legacyStore.isUnlocked()) {
            return false;
        }
        return ensureLegacyUnlocked();
    }

    private boolean ensureLegacyUnlocked() {
        if (!isLegacyAccessEnabled()) {
            return false;
        }
        if (legacyStore.isUnlocked()) {
            return true;
        }
        if (isUsingMasterPassword()) {
            return unlockUsingMasterPassword();
        }
        try {
            legacyStore.unlock(new char[0]);
            return true;
        } catch (Exception e) {
            log.error("Unable to unlock legacy password store", e);
            return false;
        }
    }

    private boolean isUsingMasterPassword() {
        return App.getGlobalSettings() != null && App.getGlobalSettings().isUsingMasterPassword();
    }

    private boolean askForLegacyFallback(String backendName) {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        int result = JOptionPane.showConfirmDialog(
                App.getAppWindow(),
                new Object[]{
                        backendName + " is not available.",
                        "Use legacy passwords.pfx fallback for saved secrets?",
                        "Choose No to keep new secrets only for this session."
                },
                "MuonSSH credential storage",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return result == JOptionPane.YES_OPTION;
    }

    private SecretStore requirePrimaryStore() throws Exception {
        SecretStore primary = getPrimaryStore();
        if (primary == null) {
            throw new IllegalStateException(expectedSystemBackendName() + " is not available");
        }
        return primary;
    }

    private void validatePrimarySecret(SecretStore primary, String alias, String expectedValue) throws Exception {
        char[] actual = primary.get(alias);
        char[] expected = hasSecretValue(expectedValue) ? expectedValue.toCharArray() : null;
        try {
            if (!Arrays.equals(actual, expected)) {
                throw new IllegalStateException("Unable to validate secret in " + primary.backendName() + " for " + alias);
            }
        } finally {
            if (actual != null) {
                Arrays.fill(actual, '\0');
            }
            if (expected != null) {
                Arrays.fill(expected, '\0');
            }
        }
    }

    private void validatePrimarySecrets(SecretStore primary, Map<String, char[]> expectedSecrets) throws Exception {
        for (Map.Entry<String, char[]> entry : expectedSecrets.entrySet()) {
            char[] actual = primary.get(entry.getKey());
            try {
                if (!Arrays.equals(actual, entry.getValue())) {
                    throw new IllegalStateException("Unable to validate migrated secret in "
                            + primary.backendName() + " for " + entry.getKey());
                }
            } finally {
                if (actual != null) {
                    Arrays.fill(actual, '\0');
                }
            }
        }
    }

    private Map<String, char[]> collectLegacySecretsForSystemStore(SavedSessionTree savedSessionTree) {
        Map<String, char[]> expected = new LinkedHashMap<>();
        Map<String, char[]> legacySecrets = legacyStore.snapshot();
        copyLegacyAlias(expected, SecretAliases.VIKUNJA_API_TOKEN, legacySecrets.get(SecretAliases.LEGACY_VIKUNJA_API_TOKEN));
        copyLegacyAlias(expected, SecretAliases.INFISICAL_CLIENT_SECRET, legacySecrets.get(SecretAliases.LEGACY_INFISICAL_CLIENT_SECRET));
        copySessionLegacyAliases(expected, legacySecrets, savedSessionTree == null ? null : savedSessionTree.getFolder());
        for (Map.Entry<String, char[]> entry : legacySecrets.entrySet()) {
            if (SecretAliases.isCanonicalAlias(entry.getKey())) {
                copyLegacyAlias(expected, entry.getKey(), entry.getValue());
            }
        }
        return expected;
    }

    private void copySessionLegacyAliases(Map<String, char[]> target, Map<String, char[]> legacySecrets, SessionFolder folder) {
        if (folder == null) {
            return;
        }
        for (SessionInfo info : folder.getItems()) {
            if (info.getId() != null && !info.getId().isBlank()) {
                copyLegacyAlias(target, SecretAliases.sshPassword(info.getId()), legacySecrets.get(info.getId()));
            }
        }
        for (SessionFolder child : folder.getFolders()) {
            copySessionLegacyAliases(target, legacySecrets, child);
        }
    }

    private void copyLegacyAlias(Map<String, char[]> target, String alias, char[] value) {
        if (alias == null || value == null || value.length == 0) {
            return;
        }
        target.put(alias, Arrays.copyOf(value, value.length));
    }

    private boolean isLegacyAccessEnabled() {
        if (legacyAccessEnabled != null) {
            return legacyAccessEnabled;
        }
        legacyAccessEnabled = !hasCompleted400MigrationMarker();
        return legacyAccessEnabled;
    }

    private boolean hasCompleted400MigrationMarker() {
        Path databasePath = configDir.toPath().resolve(Constants.VPS_LEDGER_DB_FILE);
        if (!Files.exists(databasePath)) {
            return false;
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
             PreparedStatement tableCheck = connection.prepareStatement(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'app_state'");
             ResultSet tableResult = tableCheck.executeQuery()) {
            if (!tableResult.next()) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM app_state WHERE key = ?")) {
                statement.setString(1, Constants.MIGRATION_4_0_0_COMPLETED_AT_KEY);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() && !resultSet.getString("value").isBlank();
                }
            }
        } catch (SQLException e) {
            log.debug("Unable to inspect migration marker in {}: {}", databasePath, e.getMessage());
            return false;
        }
    }

    private static SecretStore createPlatformPrimaryStore() throws Exception {
        if (IS_LINUX) {
            return new LinuxSecretServiceStore();
        }
        if (IS_WINDOWS) {
            return new WindowsCredentialStore();
        }
        return null;
    }

    private String expectedSystemBackendName() {
        if (IS_LINUX) {
            return "Secret Service";
        }
        if (IS_WINDOWS) {
            return "Windows Credential Manager";
        }
        return "System credential store";
    }

    private boolean hasSecretValue(String value) {
        return value != null && !value.isEmpty();
    }

    private SecretReadResult toSecretReadResult(char[] value, SecretReadStatus status) {
        try {
            return new SecretReadResult(new String(value), status);
        } finally {
            Arrays.fill(value, '\0');
        }
    }
}
