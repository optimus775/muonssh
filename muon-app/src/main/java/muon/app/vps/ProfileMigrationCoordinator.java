package muon.app.vps;

import lombok.extern.slf4j.Slf4j;
import muon.app.App;
import muon.app.common.PasswordStore;
import muon.app.common.settings.Settings;
import muon.app.ui.components.session.SavedSessionTree;
import muon.app.util.Constants;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
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
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class ProfileMigrationCoordinator {

    public static final String MIGRATION_VERSION = "4.0.0";
    static final String COMPLETED_AT_KEY = Constants.MIGRATION_4_0_0_COMPLETED_AT_KEY;
    static final String SOURCE_KEY = "migration_4_0_0_source";
    static final String ARCHIVED_DB_KEY = "migration_4_0_0_archived_db";
    static final String ARCHIVED_PASSWORD_STORE_KEY = "migration_4_0_0_archived_password_store";

    private static final DateTimeFormatter ARCHIVE_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    interface MigrationPrompt {
        MigrationInput prompt(MigrationInput defaults);

        void showError(String title, String message);
    }

    interface InitialPushAction {
        void push(long localUpdatedAt) throws Exception;
    }

    static final class MigrationInput {
        String infisicalBaseUrl;
        String infisicalProjectId;
        String infisicalEnvironment;
        String infisicalSecretBasePath;
        String infisicalClientId;
        String infisicalOrganizationSlug;
        String infisicalClientSecret;

        MigrationInput copy() {
            MigrationInput copy = new MigrationInput();
            copy.infisicalBaseUrl = infisicalBaseUrl;
            copy.infisicalProjectId = infisicalProjectId;
            copy.infisicalEnvironment = infisicalEnvironment;
            copy.infisicalSecretBasePath = infisicalSecretBasePath;
            copy.infisicalClientId = infisicalClientId;
            copy.infisicalOrganizationSlug = infisicalOrganizationSlug;
            copy.infisicalClientSecret = infisicalClientSecret;
            return copy;
        }
    }

    private final VpsHostRepository hostRepository;
    private final MigrationPrompt prompt;
    private final InitialPushAction initialPushAction;

    public ProfileMigrationCoordinator() {
        this(new VpsHostRepository(),
             new SwingMigrationPrompt(),
             ProfileMigrationCoordinator::pushLocalStateInBackground);
    }

    ProfileMigrationCoordinator(VpsHostRepository hostRepository,
                                MigrationPrompt prompt,
                                InitialPushAction initialPushAction) {
        this.hostRepository = hostRepository;
        this.prompt = prompt;
        this.initialPushAction = initialPushAction;
    }

    public boolean runIfNeeded() {
        FileState fileState = resolveFileState();
        if (!fileState.required) {
            return true;
        }

        MigrationInput defaults = buildDefaults();
        MigrationInput input = prompt.prompt(defaults);
        if (input == null) {
            return false;
        }

        try {
            PasswordStore passwordStore = PasswordStore.getSharedInstance();
            requireSystemStore(passwordStore);
            String archivedDatabase = archiveExistingDatabaseIfPresent(fileState.databasePath);
            hostRepository.rebuildFromLegacyJson(fileState.legacyJsonPath.toFile());
            SavedSessionTree migratedTree = hostRepository.loadTree();
            passwordStore.migrateLegacySecretsToSystemStore(migratedTree);
            String archivedPasswordStore = passwordStore.backupLegacyStoreIfPresent();
            applySettings(input, passwordStore);
            long localUpdatedAt = System.currentTimeMillis();
            hostRepository.saveAppStateValue(InfisicalSyncService.LOCAL_STATE_UPDATED_AT_KEY, String.valueOf(localUpdatedAt));
            hostRepository.saveAppStateValue(COMPLETED_AT_KEY, String.valueOf(System.currentTimeMillis()));
            hostRepository.saveAppStateValue(SOURCE_KEY, fileState.legacyJsonPath.toString());
            hostRepository.saveAppStateValue(ARCHIVED_DB_KEY, archivedDatabase == null ? "" : archivedDatabase);
            hostRepository.saveAppStateValue(ARCHIVED_PASSWORD_STORE_KEY, archivedPasswordStore == null ? "" : archivedPasswordStore);
            startInitialPush(localUpdatedAt);
            return true;
        } catch (Exception e) {
            log.error("4.0.0 profile migration failed", e);
            prompt.showError("Migration failed", "Unable to migrate the MuonSSH profile: " + describeException(e));
            return false;
        }
    }

    private static void pushLocalStateInBackground(long localUpdatedAt) {
        Thread thread = new Thread(() -> {
            try {
                new InfisicalSyncService().pushLocalStateNow(localUpdatedAt);
            } catch (Exception e) {
                log.warn("Initial Infisical push failed; continuing with the local migrated profile", e);
            }
        }, "infisical-initial-push");
        thread.setDaemon(true);
        thread.start();
    }

    private void startInitialPush(long localUpdatedAt) {
        try {
            initialPushAction.push(localUpdatedAt);
        } catch (Exception e) {
            log.warn("Initial Infisical push failed; continuing with the local migrated profile", e);
        }
    }

    private MigrationInput buildDefaults() {
        MigrationInput defaults = new MigrationInput();
        Settings settings = App.getGlobalSettings();
        defaults.infisicalBaseUrl = trimToEmpty(settings.getInfisicalBaseUrl());
        defaults.infisicalProjectId = trimToEmpty(settings.getInfisicalProjectId());
        defaults.infisicalEnvironment = trimToEmpty(settings.getInfisicalEnvironment());
        defaults.infisicalSecretBasePath = trimToEmpty(settings.getInfisicalSecretBasePath());
        defaults.infisicalClientId = trimToEmpty(settings.getInfisicalClientId());
        defaults.infisicalOrganizationSlug = trimToEmpty(settings.getInfisicalOrganizationSlug());
        try {
            String existingSecret = PasswordStore.getSharedInstance().getSecret(InfisicalClient.CLIENT_SECRET_ALIAS);
            defaults.infisicalClientSecret = existingSecret == null ? "" : existingSecret;
        } catch (Exception e) {
            log.warn("Unable to prefill saved Infisical client secret: {}", e.getMessage());
            defaults.infisicalClientSecret = "";
        }
        return defaults;
    }

    private void applySettings(MigrationInput input, PasswordStore passwordStore) throws Exception {
        Settings settings = App.getGlobalSettings();
        settings.setInfisicalBaseUrl(trimToEmpty(input.infisicalBaseUrl));
        settings.setInfisicalProjectId(trimToEmpty(input.infisicalProjectId));
        settings.setInfisicalEnvironment(trimToEmpty(input.infisicalEnvironment));
        settings.setInfisicalSecretBasePath(trimToEmpty(input.infisicalSecretBasePath));
        settings.setInfisicalClientId(trimToEmpty(input.infisicalClientId));
        settings.setInfisicalOrganizationSlug(trimToEmpty(input.infisicalOrganizationSlug));
        App.getCONTEXT().getSettingsManager().saveSettings();
        passwordStore.saveSecretToSystemStore(InfisicalClient.CLIENT_SECRET_ALIAS, trimToEmpty(input.infisicalClientSecret));
    }

    private void requireSystemStore(PasswordStore passwordStore) {
        if (passwordStore.isSystemStoreAvailable()) {
            return;
        }
        throw new IllegalStateException(passwordStore.expectedSystemStoreBackendName() + " is not available");
    }

    private String archiveExistingDatabaseIfPresent(Path databasePath) throws Exception {
        if (databasePath == null || !Files.exists(databasePath)) {
            return null;
        }
        String suffix = ARCHIVE_SUFFIX.format(LocalDateTime.now());
        Path archived = databasePath.resolveSibling("vps-ledger.pre-" + MIGRATION_VERSION + "-" + suffix + ".db");
        Files.move(databasePath, archived, StandardCopyOption.REPLACE_EXISTING);
        moveSidecarIfExists(databasePath, archived, "-wal");
        moveSidecarIfExists(databasePath, archived, "-shm");
        return archived.toString();
    }

    private void moveSidecarIfExists(Path sourceDb, Path archivedDb, String suffix) throws Exception {
        Path sidecar = sourceDb.resolveSibling(sourceDb.getFileName().toString() + suffix);
        if (!Files.exists(sidecar)) {
            return;
        }
        Path archivedSidecar = archivedDb.resolveSibling(archivedDb.getFileName().toString() + suffix);
        Files.move(sidecar, archivedSidecar, StandardCopyOption.REPLACE_EXISTING);
    }

    private FileState resolveFileState() {
        Path configDir = App.getCONTEXT().getConfigDir().toPath();
        Path legacyJson = configDir.resolve(Constants.SESSION_DB_FILE);
        Path database = configDir.resolve(Constants.VPS_LEDGER_DB_FILE);
        if (!Files.exists(legacyJson)) {
            return new FileState(false, legacyJson, database);
        }
        if (!Files.exists(database)) {
            return new FileState(true, legacyJson, database);
        }
        String completedValue = readAppStateValueRaw(database, COMPLETED_AT_KEY);
        return new FileState(completedValue == null || completedValue.isBlank(), legacyJson, database);
    }

    private String readAppStateValueRaw(Path databasePath, String key) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
             PreparedStatement tableCheck = connection.prepareStatement(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'app_state'");
             ResultSet tableResult = tableCheck.executeQuery()) {
            if (!tableResult.next()) {
                return null;
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM app_state WHERE key = ?")) {
                statement.setString(1, key);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getString("value");
                    }
                }
            }
        } catch (SQLException e) {
            log.info("Unable to inspect migration marker in {}: {}", databasePath, e.getMessage());
        }
        return null;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String describeException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
            current = current.getCause();
        }
        return error == null ? "Unknown error" : error.getClass().getSimpleName();
    }

    private static final class FileState {
        private final boolean required;
        private final Path legacyJsonPath;
        private final Path databasePath;

        private FileState(boolean required, Path legacyJsonPath, Path databasePath) {
            this.required = required;
            this.legacyJsonPath = legacyJsonPath;
            this.databasePath = databasePath;
        }
    }

    private static final class SwingMigrationPrompt implements MigrationPrompt {

        @Override
        public MigrationInput prompt(MigrationInput defaults) {
            AtomicReference<MigrationInput> result = new AtomicReference<>();
            Runnable showDialog = () -> result.set(new MigrationWizardDialog(defaults).showDialog());
            runOnEdt(showDialog);
            return result.get();
        }

        @Override
        public void showError(String title, String message) {
            runOnEdt(() -> JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE));
        }

        private void runOnEdt(Runnable runnable) {
            if (SwingUtilities.isEventDispatchThread()) {
                runnable.run();
                return;
            }
            try {
                SwingUtilities.invokeAndWait(runnable);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to display migration UI", e);
            }
        }
    }

    private static final class MigrationWizardDialog extends JDialog {
        private final JTextField txtBaseUrl = new JTextField(32);
        private final JTextField txtProjectId = new JTextField(32);
        private final JTextField txtEnvironment = new JTextField(32);
        private final JTextField txtSecretBasePath = new JTextField(32);
        private final JTextField txtClientId = new JTextField(32);
        private final JTextField txtOrganizationSlug = new JTextField(32);
        private final JPasswordField txtClientSecret = new JPasswordField(32);

        private MigrationInput result;

        private MigrationWizardDialog(MigrationInput defaults) {
            super((Frame) null, "Migration Wizard " + MIGRATION_VERSION, true);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setLayout(new BorderLayout(12, 12));

            JTextArea description = new JTextArea();
            description.setEditable(false);
            description.setLineWrap(true);
            description.setWrapStyleWord(true);
            description.setOpaque(false);
            description.setText("MuonSSH " + MIGRATION_VERSION + " will rebuild the local VPS Ledger from session-store.json "
                    + "using local data first. Infisical settings are optional and sync runs in the background when configured.\n\n"
                    + "Cancel stops the upgrade now. The wizard will open again on the next launch.");
            add(description, BorderLayout.NORTH);

            JPanel form = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(4, 4, 4, 4);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;

            addRow(form, gbc, 0, "Infisical Base URL", txtBaseUrl);
            addRow(form, gbc, 1, "Project ID", txtProjectId);
            addRow(form, gbc, 2, "Environment", txtEnvironment);
            addRow(form, gbc, 3, "Secret Base Path", txtSecretBasePath);
            addRow(form, gbc, 4, "Client ID", txtClientId);
            addRow(form, gbc, 5, "Organization Slug", txtOrganizationSlug);
            addRow(form, gbc, 6, "Client Secret", txtClientSecret);
            add(form, BorderLayout.CENTER);

            JButton btnCancel = new JButton("Cancel");
            JButton btnContinue = new JButton("Migrate");
            btnCancel.addActionListener(e -> {
                result = null;
                dispose();
            });
            btnContinue.addActionListener(e -> submit());

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            actions.add(btnCancel);
            actions.add(btnContinue);
            add(actions, BorderLayout.SOUTH);

            applyDefaults(defaults);
            setMinimumSize(new Dimension(720, 380));
            pack();
            setLocationRelativeTo(null);
        }

        private MigrationInput showDialog() {
            setVisible(true);
            return result;
        }

        private void addRow(JPanel form, GridBagConstraints gbc, int row, String label, JComponent field) {
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0.0;
            form.add(new JLabel(label), gbc);
            gbc.gridx = 1;
            gbc.weightx = 1.0;
            form.add(field, gbc);
        }

        private void applyDefaults(MigrationInput defaults) {
            MigrationInput value = defaults == null ? new MigrationInput() : defaults.copy();
            txtBaseUrl.setText(emptyToDefault(value.infisicalBaseUrl, "https://app.infisical.com"));
            txtProjectId.setText(value.infisicalProjectId);
            txtEnvironment.setText(emptyToDefault(value.infisicalEnvironment, "prod"));
            txtSecretBasePath.setText(emptyToDefault(value.infisicalSecretBasePath, "/vps-manager"));
            txtClientId.setText(value.infisicalClientId);
            txtOrganizationSlug.setText(value.infisicalOrganizationSlug);
            txtClientSecret.setText(value.infisicalClientSecret == null ? "" : value.infisicalClientSecret);
        }

        private String emptyToDefault(String value, String defaultValue) {
            return value == null || value.isBlank() ? defaultValue : value;
        }

        private void submit() {
            MigrationInput input = new MigrationInput();
            input.infisicalBaseUrl = txtBaseUrl.getText();
            input.infisicalProjectId = txtProjectId.getText();
            input.infisicalEnvironment = txtEnvironment.getText();
            input.infisicalSecretBasePath = txtSecretBasePath.getText();
            input.infisicalClientId = txtClientId.getText();
            input.infisicalOrganizationSlug = txtOrganizationSlug.getText();
            input.infisicalClientSecret = new String(txtClientSecret.getPassword());

            String validationError = validate(input);
            if (validationError != null) {
                JOptionPane.showMessageDialog(this, validationError, "Migration Wizard", JOptionPane.ERROR_MESSAGE);
                return;
            }

            result = input;
            dispose();
        }

        private String validate(MigrationInput input) {
            return null;
        }
    }
}
