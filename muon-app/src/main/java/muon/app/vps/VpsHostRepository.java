package muon.app.vps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import muon.app.App;
import muon.app.ui.components.session.SavedSessionTree;
import muon.app.ui.components.session.SessionFolder;
import muon.app.ui.components.session.SessionInfo;
import muon.app.ui.components.session.SessionStore;
import muon.app.ui.components.session.dialog.TreeManager;
import muon.app.util.Constants;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
public class VpsHostRepository {
    private static final String TECHNICAL_ROOT_NAME = "Empty_Root";

    private final ObjectMapper objectMapper;
    private final VpsProviderRepository providerRepository;

    public VpsHostRepository() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectMapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
        this.providerRepository = new VpsProviderRepository();
    }

    public synchronized SavedSessionTree loadTree() {
        try {
            VpsDatabaseManager.migrate();
            importLegacyJsonIfNeeded();
            SavedSessionTree tree = readTreeFromDatabase();
            if (tree != null) {
                normalizeTechnicalRoot(tree);
                return tree;
            }
        } catch (Exception e) {
            log.error("Unable to load VPS Ledger database", e);
        }
        return createDefaultTree();
    }

    public synchronized void saveTree(SessionFolder folder, String lastSelectionPath) throws IOException {
        saveTree(folder, lastSelectionPath, true);
    }

    synchronized void saveTree(SessionFolder folder, String lastSelectionPath, boolean hydrateProviders) throws IOException {
        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection()) {
                connection.setAutoCommit(false);
                try {
                    writeTree(connection, folder, lastSelectionPath, hydrateProviders);
                    connection.commit();
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new IOException("Unable to save VPS Ledger database", e);
        } catch (Exception e) {
            throw new IOException("Unable to save VPS Ledger data", e);
        }
    }

    public synchronized void replaceSnapshotState(SavedSessionTree tree, List<ProviderRecord> providers, long localStateUpdatedAt)
            throws IOException {
        SavedSessionTree snapshotTree = tree == null ? createDefaultTree() : tree;
        if (snapshotTree.getFolder() == null) {
            snapshotTree.setFolder(createDefaultTree().getFolder());
        }
        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection()) {
                connection.setAutoCommit(false);
                try {
                    providerRepository.replaceProviders(connection, providers);
                    writeTree(connection, snapshotTree.getFolder(), snapshotTree.getLastSelection(), false);
                    saveState(connection, "infisical_local_state_updated_at", String.valueOf(localStateUpdatedAt));
                    connection.commit();
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new IOException("Unable to replace VPS Ledger snapshot", e);
        } catch (Exception e) {
            throw new IOException("Unable to replace VPS Ledger snapshot data", e);
        }
    }

    public synchronized List<SessionInfo> listHosts() {
        List<SessionInfo> sessions = new ArrayList<>();
        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(hostSelectSql("WHERE h.deleted = 0 ORDER BY LOWER(h.name)"))) {
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        sessions.add(readHost(rs));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Unable to list VPS Ledger hosts", e);
        }
        return sessions;
    }

    public synchronized void upsertHostOnly(SessionInfo info) {
        if (info == null || info.getId() == null) {
            return;
        }
        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection()) {
                upsertHost(connection, info);
            }
        } catch (Exception e) {
            log.error("Unable to update VPS Ledger host {}", info.getId(), e);
        }
    }

    public synchronized void recordConflict(String hostId, String localJson, String remoteJson) {
        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO sync_conflicts(host_id, local_json, remote_json, created_at) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, hostId);
                statement.setString(2, localJson);
                statement.setString(3, remoteJson);
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            }
        } catch (Exception e) {
            log.error("Unable to record sync conflict for {}", hostId, e);
        }
    }

    public synchronized String toHostJson(SessionInfo info) throws IOException {
        return objectMapper.writeValueAsString(info);
    }

    public synchronized SessionInfo fromHostJson(String json) throws IOException {
        return objectMapper.readValue(json, SessionInfo.class);
    }

    public synchronized String getAppStateValue(String key) {
        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection()) {
                return readState(connection, key);
            }
        } catch (Exception e) {
            log.error("Unable to read app state {}", key, e);
            return null;
        }
    }

    public synchronized void saveAppStateValue(String key, String value) {
        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection()) {
                saveState(connection, key, value);
            }
        } catch (Exception e) {
            log.error("Unable to save app state {}", key, e);
        }
    }

    public synchronized void rebuildFromLegacyJson(File legacyFile) throws IOException {
        SavedSessionTree legacyTree = loadLegacyTree(legacyFile);
        if (legacyFile != null) {
            backupLegacyFile(legacyFile);
        }
        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection()) {
                connection.setAutoCommit(false);
                try {
                    providerRepository.replaceProviders(connection, Collections.emptyList());
                    writeTree(connection, legacyTree.getFolder(), legacyTree.getLastSelection(), false);
                    connection.commit();
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new IOException("Unable to rebuild VPS Ledger database from legacy JSON", e);
        } catch (Exception e) {
            throw new IOException("Unable to rebuild VPS Ledger data from legacy JSON", e);
        }
    }

    private void importLegacyJsonIfNeeded() throws Exception {
        try (Connection connection = VpsDatabaseManager.openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM folders")) {
            if (resultSet.next() && resultSet.getInt(1) > 0) {
                return;
            }
        }

        File legacyFile = new File(App.getCONTEXT().getConfigDir(), Constants.SESSION_DB_FILE);
        if (!legacyFile.exists()) {
            saveTree(createDefaultTree().getFolder(), null);
            return;
        }

        log.info("Importing legacy session-store.json into VPS Ledger SQLite database");
        SavedSessionTree legacyTree = loadLegacyTree(legacyFile);
        saveTree(legacyTree.getFolder(), legacyTree.getLastSelection(), false);
        backupLegacyFile(legacyFile);
    }

    private void backupLegacyFile(File legacyFile) {
        File backup = new File(App.getCONTEXT().getConfigDir(), Constants.LEGACY_SESSION_BACKUP_FILE);
        if (backup.exists()) {
            return;
        }
        try {
            Files.copy(legacyFile.toPath(), backup.toPath());
        } catch (IOException e) {
            log.error("Unable to back up legacy session store", e);
        }
    }

    private SavedSessionTree loadLegacyTree(File legacyFile) throws IOException {
        if (legacyFile == null || !legacyFile.exists()) {
            return createDefaultTree();
        }
        SavedSessionTree legacyTree = objectMapper.readValue(SessionStore.preprocessJson(legacyFile), new TypeReference<>() {
        });
        if (legacyTree.getFolder() == null) {
            legacyTree = createDefaultTree();
        }
        ensureIds(legacyTree.getFolder());
        return legacyTree;
    }

    private SavedSessionTree readTreeFromDatabase() throws Exception {
        try (Connection connection = VpsDatabaseManager.openConnection()) {
            List<SessionFolder> roots = readFolders(connection, null);
            if (roots.isEmpty()) {
                return null;
            }
            SavedSessionTree tree = new SavedSessionTree();
            tree.setFolder(roots.get(0));
            tree.setLastSelection(readState(connection, "last_selection"));
            return tree;
        }
    }

    private void normalizeTechnicalRoot(SavedSessionTree tree) {
        if (tree == null || tree.getFolder() == null) {
            return;
        }
        SessionFolder root = tree.getFolder();
        if (!isTechnicalRoot(root)) {
            return;
        }
        if (root.getFolders().size() == 1) {
            tree.setFolder(root.getFolders().get(0));
        }
    }

    private boolean isTechnicalRoot(SessionFolder folder) {
        return folder != null
                && TECHNICAL_ROOT_NAME.equals(folder.getName())
                && folder.getItems().isEmpty();
    }

    private List<SessionFolder> readFolders(Connection connection, String parentId) throws Exception {
        List<SessionFolder> folders = new ArrayList<>();
        String sql = parentId == null
                ? "SELECT id, name FROM folders WHERE parent_id IS NULL ORDER BY position, LOWER(name)"
                : "SELECT id, name FROM folders WHERE parent_id = ? ORDER BY position, LOWER(name)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parentId != null) {
                statement.setString(1, parentId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    SessionFolder folder = new SessionFolder();
                    folder.setId(rs.getString("id"));
                    folder.setName(rs.getString("name"));
                    folder.setFolders(readFolders(connection, folder.getId()));
                    folder.setItems(readHosts(connection, folder.getId()));
                    folders.add(folder);
                }
            }
        }
        return folders;
    }

    private List<SessionInfo> readHosts(Connection connection, String folderId) throws Exception {
        List<SessionInfo> hosts = new ArrayList<>();
        String sql = hostSelectSql(
                "JOIN folder_hosts fh ON fh.host_id = h.id "
                + "WHERE fh.folder_id = ? AND h.deleted = 0 "
                + "ORDER BY fh.position, LOWER(h.name)");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, folderId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    hosts.add(readHost(rs));
                }
            }
        }
        return hosts;
    }

    private String hostSelectSql(String suffix) {
        return "SELECT h.*, p.name AS provider_name, p.website AS provider_website, "
                + "p.account_id AS provider_account_id "
                + "FROM hosts h "
                + "LEFT JOIN providers p ON p.id = h.provider_id "
                + suffix;
    }

    private SessionInfo readHost(ResultSet rs) throws Exception {
        SessionInfo info = objectMapper.readValue(rs.getString("session_json"), SessionInfo.class);
        info.setProviderId(rs.getString("provider_id"));
        String providerName = rs.getString("provider_name");
        if (providerName != null && !providerName.isBlank()) {
            info.setProvider(providerName);
        } else if (info.getProvider() == null || info.getProvider().isBlank()) {
            info.setProvider(rs.getString("provider"));
        }
        String providerWebsite = rs.getString("provider_website");
        if ((info.getProviderUrl() == null || info.getProviderUrl().isBlank()) && providerWebsite != null && !providerWebsite.isBlank()) {
            info.setProviderUrl(providerWebsite);
        }
        if (info.getAccountId() == null || info.getAccountId().isBlank()) {
            info.setAccountId(rs.getString("provider_account_id"));
        }
        info.setBillingPeriodType(Objects.toString(rs.getString("billing_period_type"), info.getBillingPeriodType()));
        int billingPeriodDays = rs.getInt("billing_period_days");
        if (!rs.wasNull()) {
            info.setBillingPeriodDays(billingPeriodDays);
        } else if (info.getBillingPeriodDays() <= 0) {
            info.setBillingPeriodDays(info.getBillingCycleDays());
        }
        info.setHourlyRate(rs.getString("hourly_rate"));
        info.setNextBalanceCheckDate(rs.getString("next_balance_check_date"));
        return info;
    }

    private void clearTreeTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM folder_hosts");
            statement.executeUpdate("DELETE FROM folders");
            statement.executeUpdate("DELETE FROM hosts");
        }
    }

    private void writeTree(Connection connection, SessionFolder folder, String lastSelectionPath, boolean hydrateProviders) throws Exception {
        clearTreeTables(connection);
        saveFolder(connection, null, folder, 0, hydrateProviders);
        saveState(connection, "last_selection", lastSelectionPath);
    }

    private void saveFolder(Connection connection, String parentId, SessionFolder folder, int position, boolean hydrateProviders) throws Exception {
        if (folder.getId() == null || folder.getId().isEmpty()) {
            folder.setId(UUID.randomUUID().toString());
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO folders(id, parent_id, name, position, updated_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, folder.getId());
            statement.setString(2, parentId);
            statement.setString(3, Objects.toString(folder.getName(), ""));
            statement.setInt(4, position);
            statement.setLong(5, now);
            statement.executeUpdate();
        }

        int hostPosition = 0;
        for (SessionInfo info : folder.getItems()) {
            if (info.getId() == null || info.getId().isEmpty()) {
                info.setId(UUID.randomUUID().toString());
                info.setUpdatedAt(System.currentTimeMillis());
            }
            upsertHost(connection, info, hydrateProviders);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO folder_hosts(folder_id, host_id, position) VALUES (?, ?, ?)")) {
                statement.setString(1, folder.getId());
                statement.setString(2, info.getId());
                statement.setInt(3, hostPosition++);
                statement.executeUpdate();
            }
        }

        int folderPosition = 0;
        for (SessionFolder child : folder.getFolders()) {
            saveFolder(connection, folder.getId(), child, folderPosition++, hydrateProviders);
        }
    }

    private void upsertHost(Connection connection, SessionInfo info) throws Exception {
        upsertHost(connection, info, true);
    }

    private void upsertHost(Connection connection, SessionInfo info, boolean hydrateProviders) throws Exception {
        if (hydrateProviders) {
            hydrateProviderId(connection, info);
        } else {
            info.setProviderId(null);
        }
        if (info.getBillingPeriodType() == null || info.getBillingPeriodType().isBlank()) {
            info.setBillingPeriodType("fixed_period");
        }
        if (info.getBillingPeriodDays() <= 0) {
            info.setBillingPeriodDays(info.getBillingCycleDays() > 0 ? info.getBillingCycleDays() : 30);
        }
        String json = objectMapper.writeValueAsString(info);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO hosts("
                        + "id, name, host, provider_id, provider, provider_url, account_id, billing_period_type, "
                        + "billing_cycle, billing_cycle_days, billing_period_days, price, currency, next_payment_date, "
                        + "hourly_rate, next_balance_check_date, cancel_by_date, auto_pay, status, tags, description, "
                        + "external_refs, vikunja_task_id, sync_private_key, sync_public_key, deleted, updated_at, session_json"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET "
                        + "name = excluded.name, host = excluded.host, provider_id = excluded.provider_id, "
                        + "provider = excluded.provider, provider_url = excluded.provider_url, account_id = excluded.account_id, "
                        + "billing_period_type = excluded.billing_period_type, billing_cycle = excluded.billing_cycle, "
                        + "billing_cycle_days = excluded.billing_cycle_days, billing_period_days = excluded.billing_period_days, "
                        + "price = excluded.price, currency = excluded.currency, next_payment_date = excluded.next_payment_date, "
                        + "hourly_rate = excluded.hourly_rate, next_balance_check_date = excluded.next_balance_check_date, "
                        + "cancel_by_date = excluded.cancel_by_date, auto_pay = excluded.auto_pay, status = excluded.status, "
                        + "tags = excluded.tags, description = excluded.description, external_refs = excluded.external_refs, "
                        + "vikunja_task_id = excluded.vikunja_task_id, sync_private_key = excluded.sync_private_key, "
                        + "sync_public_key = excluded.sync_public_key, deleted = excluded.deleted, updated_at = excluded.updated_at, "
                        + "session_json = excluded.session_json")) {
            statement.setString(1, info.getId());
            statement.setString(2, info.getName());
            statement.setString(3, info.getHost());
            statement.setString(4, info.getProviderId());
            statement.setString(5, info.getProvider());
            statement.setString(6, info.getProviderUrl());
            statement.setString(7, info.getAccountId());
            statement.setString(8, info.getBillingPeriodType());
            statement.setString(9, info.getBillingCycle());
            statement.setInt(10, info.getBillingCycleDays());
            statement.setInt(11, info.getBillingPeriodDays());
            statement.setString(12, info.getPrice());
            statement.setString(13, info.getCurrency());
            statement.setString(14, info.getNextPaymentDate());
            statement.setString(15, info.getHourlyRate());
            statement.setString(16, info.getNextBalanceCheckDate());
            statement.setString(17, info.getCancelByDate());
            statement.setInt(18, info.isAutoPay() ? 1 : 0);
            statement.setString(19, info.getVpsStatus());
            statement.setString(20, info.getTags());
            statement.setString(21, info.getDescription());
            statement.setString(22, info.getExternalRefs());
            if (info.getVikunjaTaskId() == null) {
                statement.setObject(23, null);
            } else {
                statement.setLong(23, info.getVikunjaTaskId());
            }
            statement.setInt(24, info.isSyncPrivateKey() ? 1 : 0);
            statement.setInt(25, info.isSyncPublicKey() ? 1 : 0);
            statement.setInt(26, 0);
            statement.setLong(27, info.getUpdatedAt());
            statement.setString(28, json);
            statement.executeUpdate();
        }
    }

    private void hydrateProviderId(Connection connection, SessionInfo info) throws Exception {
        if (info.getProviderId() != null && !info.getProviderId().isBlank()) {
            return;
        }
        String providerId = providerRepository.createProviderFromHost(connection, info.getProvider(),
                info.getProviderUrl(), info.getAccountId());
        if (providerId != null) {
            info.setProviderId(providerId);
        }
    }

    private void saveState(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO app_state(key, value) VALUES (?, ?)")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private String readState(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM app_state WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        }
        return null;
    }

    private SavedSessionTree createDefaultTree() {
        SessionFolder rootFolder = new SessionFolder();
        rootFolder.setId(TreeManager.getNewUuid(new javax.swing.tree.DefaultMutableTreeNode("root")));
        rootFolder.setName("My sites");
        SavedSessionTree tree = new SavedSessionTree();
        tree.setFolder(rootFolder);
        return tree;
    }

    private void ensureIds(SessionFolder folder) {
        if (folder.getId() == null || folder.getId().isEmpty()) {
            folder.setId(UUID.randomUUID().toString());
        }
        for (SessionInfo info : folder.getItems()) {
            if (info.getId() == null || info.getId().isEmpty()) {
                info.setId(UUID.randomUUID().toString());
            }
        }
        for (SessionFolder child : folder.getFolders()) {
            ensureIds(child);
        }
    }
}
