package muon.app.vps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import muon.app.App;
import muon.app.common.PasswordStore;
import muon.app.common.PasswordStore.SecretReadResult;
import muon.app.common.PasswordStore.SecretReadStatus;
import muon.app.common.secrets.SecretAliases;
import muon.app.common.settings.Settings;
import muon.app.ui.AppWindow;
import muon.app.ui.components.session.HopEntry;
import muon.app.ui.components.session.SavedSessionTree;
import muon.app.ui.components.session.SessionFolder;
import muon.app.ui.components.session.SessionInfo;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class InfisicalSyncService {

    static final String APP_STATE_SECRET_NAME = "APP_STATE_JSON";
    static final String LOCAL_STATE_UPDATED_AT_KEY = "infisical_local_state_updated_at";
    private static final long SYNC_INTERVAL_MINUTES = 20;
    private static final DateTimeFormatter STATUS_TIME_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    private final VpsHostRepository hostRepository;
    private final VpsProviderRepository providerRepository;
    private final InfisicalClient infisicalClient;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private final Object syncLock = new Object();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean pendingRemoteApply = new AtomicBoolean(false);
    private final AtomicInteger blockingEditors = new AtomicInteger(0);

    private boolean syncRunning;
    private boolean rerunRequested;

    public InfisicalSyncService() {
        this(new VpsHostRepository(),
             new VpsProviderRepository(),
             new InfisicalClient(),
             new ObjectMapper(),
             Executors.newSingleThreadScheduledExecutor(r -> {
                 Thread thread = new Thread(r, "infisical-sync");
                 thread.setDaemon(true);
                 return thread;
             }));
    }

    InfisicalSyncService(VpsHostRepository hostRepository, VpsProviderRepository providerRepository,
                         InfisicalClient infisicalClient, ObjectMapper objectMapper,
                         ScheduledExecutorService scheduler) {
        this.hostRepository = hostRepository;
        this.providerRepository = providerRepository;
        this.infisicalClient = infisicalClient;
        this.objectMapper = objectMapper;
        this.scheduler = scheduler;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        updateStatus("Infisical: syncing...");
        scheduler.scheduleWithFixedDelay(() -> requestSync("periodic"),
                                         SYNC_INTERVAL_MINUTES,
                                         SYNC_INTERVAL_MINUTES,
                                         TimeUnit.MINUTES);
        requestSync("startup");
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    public void requestManualRefresh() {
        requestSync("manual");
    }

    public void pushLocalStateNow(long localUpdatedAt) throws Exception {
        Settings settings = App.getGlobalSettings();
        if (!isConfigured(settings)) {
            throw new IllegalStateException("Configure Infisical base URL, project ID, environment and client ID first");
        }

        PasswordStore passwordStore = PasswordStore.getSharedInstance();
        if (!passwordStore.unlockLegacySecretsIfNeeded()) {
            throw new IllegalStateException("Unlock the saved session secrets before running the initial Infisical push");
        }
        SecretReadResult secretResult = passwordStore.getSecretWithoutPrompt(InfisicalClient.CLIENT_SECRET_ALIAS);
        if (secretResult.getStatus() == SecretReadStatus.LOCKED) {
            throw new IllegalStateException("Unlock the saved secrets before running the initial Infisical push");
        }
        if (secretResult.getValue() == null || secretResult.getValue().isBlank()) {
            throw new IllegalStateException("Infisical client secret is empty");
        }

        String token = infisicalClient.login(settings, secretResult.getValue());
        try {
            pushLocalState(settings, token, localUpdatedAt);
        } catch (LockedSecretsException e) {
            throw new IllegalStateException("Unlock the saved session secrets before running the initial Infisical push", e);
        }
    }

    public void notifyLocalStateChanged() {
        hostRepository.saveAppStateValue(LOCAL_STATE_UPDATED_AT_KEY, String.valueOf(System.currentTimeMillis()));
        requestSync("local-change");
    }

    public void editorOpened() {
        blockingEditors.incrementAndGet();
    }

    public void editorClosed() {
        int remaining = blockingEditors.updateAndGet(value -> Math.max(0, value - 1));
        if (remaining == 0 && pendingRemoteApply.get()) {
            requestSync("pending-remote-apply");
        }
    }

    InfisicalAppState buildLocalState(long updatedAt) throws Exception {
        SavedSessionTree tree = hostRepository.loadTree();
        List<ProviderRecord> providers = providerRepository.listProviders();
        Map<String, InfisicalAppState.HostSecretState> hostSecrets = new LinkedHashMap<>();
        PasswordStore passwordStore = PasswordStore.getSharedInstance();

        for (SessionInfo info : collectSessions(tree.getFolder())) {
            if (info.getId() == null || info.getId().isBlank()) {
                continue;
            }
            InfisicalAppState.HostSecretState hostSecret = new InfisicalAppState.HostSecretState();
            hostSecret.setPrivateKeyPath(info.getPrivateKeyFile());
            hostSecret.setPrivateKeyContent(readFileIfPresent(info.getPrivateKeyFile()));
            hostSecret.setPublicKeyContent(readPublicKeyIfPresent(info));
            hostSecret.setSshPassword(readRequiredSecret(passwordStore, SecretAliases.sshPassword(info.getId())));
            hostSecret.setProxyPassword(readRequiredSecret(passwordStore, SecretAliases.proxyPassword(info.getId())));

            Map<String, String> jumpPasswords = new LinkedHashMap<>();
            for (HopEntry hop : info.getJumpHosts()) {
                if (hop.getId() == null || hop.getId().isBlank()) {
                    continue;
                }
                jumpPasswords.put(hop.getId(),
                                  readRequiredSecret(passwordStore, SecretAliases.jumpPassword(info.getId(), hop.getId())));
            }
            hostSecret.setJumpPasswords(jumpPasswords);
            hostSecrets.put(info.getId(), hostSecret);
        }

        InfisicalAppState state = new InfisicalAppState();
        state.setSchema(VpsDatabaseManager.getCurrentSchema());
        state.setUpdatedAt(updatedAt);
        state.setLastSelection(tree.getLastSelection());
        state.setTree(tree);
        state.setProviders(providers);
        state.setHostSecrets(hostSecrets);
        return state;
    }

    void applyRemoteState(InfisicalAppState remoteState) throws Exception {
        SavedSessionTree previousTree = hostRepository.loadTree();
        SavedSessionTree remoteTree = prepareRemoteTree(remoteState);
        Map<String, InfisicalAppState.HostSecretState> hostSecrets = safeHostSecrets(remoteState);

        hostRepository.replaceSnapshotState(remoteTree, safeProviders(remoteState), remoteState.getUpdatedAt());
        syncLocalSecrets(previousTree, remoteTree, hostSecrets);
        restoreKeyFiles(remoteTree, hostSecrets);
        pendingRemoteApply.set(false);
    }

    private void requestSync(String reason) {
        if (!started.get()) {
            return;
        }
        synchronized (syncLock) {
            if (syncRunning) {
                rerunRequested = true;
                return;
            }
            syncRunning = true;
        }
        scheduler.execute(() -> runSyncLoop(reason));
    }

    private void runSyncLoop(String reason) {
        try {
            while (true) {
                performSync(reason);
                synchronized (syncLock) {
                    if (!rerunRequested) {
                        syncRunning = false;
                        return;
                    }
                    rerunRequested = false;
                }
            }
        } finally {
            synchronized (syncLock) {
                if (syncRunning && !rerunRequested) {
                    syncRunning = false;
                }
            }
        }
    }

    private void performSync(String reason) {
        Settings settings = App.getGlobalSettings();
        if (!isConfigured(settings)) {
            updateStatus("Infisical: not configured");
            return;
        }

        try {
            PasswordStore passwordStore = PasswordStore.getSharedInstance();
            SecretReadResult secretResult = passwordStore.getSecretWithoutPrompt(InfisicalClient.CLIENT_SECRET_ALIAS);
            if (secretResult.getStatus() == SecretReadStatus.LOCKED) {
                updateStatus("Infisical: secrets locked");
                return;
            }
            if (secretResult.getValue() == null || secretResult.getValue().isBlank()) {
                updateStatus("Infisical: not configured");
                return;
            }

            updateStatus("Infisical: syncing...");

            String token = infisicalClient.login(settings, secretResult.getValue());
            long localUpdatedAt = resolveLocalStateUpdatedAt();
            String secretPath = normalizeBasePath(settings.getInfisicalSecretBasePath());
            String remoteJson = infisicalClient.readSecret(settings, token, secretPath, APP_STATE_SECRET_NAME);

            if (remoteJson == null || remoteJson.isBlank()) {
                pushLocalState(settings, token, localUpdatedAt);
                updateLastSyncStatus();
                return;
            }

            JsonNode remoteRoot = objectMapper.readTree(remoteJson);
            boolean legacyProviderPayload = hasLegacyProviderPayload(remoteRoot.path("providers"));
            InfisicalAppState remoteState = objectMapper.treeToValue(remoteRoot, InfisicalAppState.class);
            long remoteUpdatedAt = remoteState == null ? 0L : remoteState.getUpdatedAt();
            if (remoteUpdatedAt > localUpdatedAt) {
                if (blockingEditors.get() > 0) {
                    pendingRemoteApply.set(true);
                    log.info("Deferring Infisical apply while editor dialog is open");
                    return;
                }
                applyRemoteState(remoteState);
                if (legacyProviderPayload) {
                    hostRepository.saveAppStateValue(LOCAL_STATE_UPDATED_AT_KEY, String.valueOf(System.currentTimeMillis()));
                }
                updateLastSyncStatus();
                return;
            }

            pushLocalState(settings, token, localUpdatedAt);
            updateLastSyncStatus();
        } catch (LockedSecretsException e) {
            log.info("Skipping Infisical sync because required secrets are locked");
            updateStatus("Infisical: secrets locked");
        } catch (InfisicalClient.UnsupportedApiVersionException e) {
            log.error("Infisical sync requires newer Infisical API routes", e);
            updateStatus("Infisical: unsupported server version");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Infisical sync interrupted", e);
            updateStatus("Infisical: unavailable");
        } catch (Exception e) {
            log.error("Infisical sync failed during {}", reason, e);
            updateStatus("Infisical: unavailable");
        }
    }

    private void pushLocalState(Settings settings, String token, long localUpdatedAt) throws Exception {
        InfisicalAppState localState = buildLocalState(localUpdatedAt);
        String secretPath = normalizeBasePath(settings.getInfisicalSecretBasePath());
        infisicalClient.createOrUpdateSecret(settings,
                                             token,
                                             secretPath,
                                             APP_STATE_SECRET_NAME,
                                             objectMapper.writeValueAsString(localState));
        pendingRemoteApply.set(false);
    }

    private long resolveLocalStateUpdatedAt() {
        String storedValue = hostRepository.getAppStateValue(LOCAL_STATE_UPDATED_AT_KEY);
        if (storedValue != null && !storedValue.isBlank()) {
            try {
                long parsed = Long.parseLong(storedValue);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid local Infisical state timestamp: {}", storedValue);
            }
        }
        long resolved = calculateDefaultLocalStateTimestamp();
        hostRepository.saveAppStateValue(LOCAL_STATE_UPDATED_AT_KEY, String.valueOf(resolved));
        return resolved;
    }

    private long calculateDefaultLocalStateTimestamp() {
        long resolved = 0L;
        SavedSessionTree tree = hostRepository.loadTree();
        for (SessionInfo info : collectSessions(tree.getFolder())) {
            resolved = Math.max(resolved, info.getUpdatedAt());
        }
        for (ProviderRecord provider : providerRepository.listProviders()) {
            resolved = Math.max(resolved, provider.getUpdatedAt());
        }
        return resolved > 0 ? resolved : System.currentTimeMillis();
    }

    private boolean hasLegacyProviderPayload(JsonNode providersNode) {
        if (providersNode == null || !providersNode.isArray()) {
            return false;
        }
        for (JsonNode providerNode : providersNode) {
            if (providerNode == null || providerNode.isNull()) {
                continue;
            }
            if (providerNode.has("panelUrl") || providerNode.has("billingUrl")
                    || providerNode.has("panel_url") || providerNode.has("billing_url")) {
                return true;
            }
            if (providerNode.path("slug").asText("").isBlank()) {
                return true;
            }
        }
        return false;
    }

    private SavedSessionTree prepareRemoteTree(InfisicalAppState remoteState) {
        SavedSessionTree tree = remoteState == null ? null : remoteState.getTree();
        if (tree == null) {
            tree = createDefaultTree();
        }
        if (tree.getFolder() == null) {
            tree.setFolder(createDefaultTree().getFolder());
        }
        tree.setLastSelection(remoteState == null ? null : remoteState.getLastSelection());
        Map<String, InfisicalAppState.HostSecretState> hostSecrets = safeHostSecrets(remoteState);
        for (SessionInfo info : collectSessions(tree.getFolder())) {
            InfisicalAppState.HostSecretState hostSecret = hostSecrets.get(info.getId());
            if (hostSecret == null) {
                continue;
            }
            if ((info.getPrivateKeyFile() == null || info.getPrivateKeyFile().isBlank())
                    && hostSecret.getPrivateKeyPath() != null
                    && !hostSecret.getPrivateKeyPath().isBlank()) {
                info.setPrivateKeyFile(hostSecret.getPrivateKeyPath());
            }
        }
        return tree;
    }

    private void syncLocalSecrets(SavedSessionTree previousTree, SavedSessionTree remoteTree,
                                  Map<String, InfisicalAppState.HostSecretState> hostSecrets) {
        try {
            PasswordStore passwordStore = PasswordStore.getSharedInstance();
            Map<String, SessionInfo> previousHosts = indexHosts(previousTree);
            Map<String, SessionInfo> remoteHosts = indexHosts(remoteTree);

            for (Map.Entry<String, SessionInfo> entry : previousHosts.entrySet()) {
                if (!remoteHosts.containsKey(entry.getKey())) {
                    deleteHostSecrets(passwordStore, entry.getValue());
                    continue;
                }
                deleteRemovedJumpSecrets(passwordStore, entry.getValue(), remoteHosts.get(entry.getKey()));
            }

            for (Map.Entry<String, SessionInfo> entry : remoteHosts.entrySet()) {
                String hostId = entry.getKey();
                if (hostId == null || hostId.isBlank()) {
                    continue;
                }
                InfisicalAppState.HostSecretState hostSecret = hostSecrets.get(hostId);
                writeSecretQuietly(passwordStore, SecretAliases.sshPassword(hostId),
                                   hostSecret == null ? null : hostSecret.getSshPassword());
                writeSecretQuietly(passwordStore, SecretAliases.proxyPassword(hostId),
                                   hostSecret == null ? null : hostSecret.getProxyPassword());
                Map<String, String> jumpPasswords = hostSecret == null
                        ? Collections.emptyMap()
                        : safeJumpPasswords(hostSecret);
                for (HopEntry hop : entry.getValue().getJumpHosts()) {
                    if (hop.getId() == null || hop.getId().isBlank()) {
                        continue;
                    }
                    writeSecretQuietly(passwordStore,
                                       SecretAliases.jumpPassword(hostId, hop.getId()),
                                       jumpPasswords.get(hop.getId()));
                }
            }
        } catch (Exception e) {
            log.error("Unable to persist remote secrets locally", e);
        }
    }

    private void restoreKeyFiles(SavedSessionTree remoteTree,
                                 Map<String, InfisicalAppState.HostSecretState> hostSecrets) {
        for (SessionInfo info : collectSessions(remoteTree.getFolder())) {
            if (info.getId() == null || info.getId().isBlank()) {
                continue;
            }
            InfisicalAppState.HostSecretState hostSecret = hostSecrets.get(info.getId());
            if (hostSecret == null) {
                continue;
            }
            String keyPath = firstNonBlank(info.getPrivateKeyFile(), hostSecret.getPrivateKeyPath());
            if (keyPath == null || keyPath.isBlank()) {
                continue;
            }
            writeFileIfMissing(keyPath, hostSecret.getPrivateKeyContent());
            writeFileIfMissing(keyPath + ".pub", hostSecret.getPublicKeyContent());
        }
    }

    private void deleteHostSecrets(PasswordStore passwordStore, SessionInfo info) {
        if (info == null || info.getId() == null || info.getId().isBlank()) {
            return;
        }
        writeSecretQuietly(passwordStore, SecretAliases.sshPassword(info.getId()), null);
        writeSecretQuietly(passwordStore, SecretAliases.proxyPassword(info.getId()), null);
        for (HopEntry hop : info.getJumpHosts()) {
            if (hop.getId() == null || hop.getId().isBlank()) {
                continue;
            }
            writeSecretQuietly(passwordStore, SecretAliases.jumpPassword(info.getId(), hop.getId()), null);
        }
    }

    private void deleteRemovedJumpSecrets(PasswordStore passwordStore, SessionInfo previous, SessionInfo remote) {
        if (previous == null || remote == null || previous.getId() == null || previous.getId().isBlank()) {
            return;
        }
        Map<String, HopEntry> remoteHops = new LinkedHashMap<>();
        for (HopEntry hop : remote.getJumpHosts()) {
            if (hop.getId() != null && !hop.getId().isBlank()) {
                remoteHops.put(hop.getId(), hop);
            }
        }
        for (HopEntry hop : previous.getJumpHosts()) {
            if (hop.getId() == null || hop.getId().isBlank()) {
                continue;
            }
            if (!remoteHops.containsKey(hop.getId())) {
                writeSecretQuietly(passwordStore, SecretAliases.jumpPassword(previous.getId(), hop.getId()), null);
            }
        }
    }

    private Map<String, SessionInfo> indexHosts(SavedSessionTree tree) {
        Map<String, SessionInfo> hosts = new LinkedHashMap<>();
        if (tree == null || tree.getFolder() == null) {
            return hosts;
        }
        for (SessionInfo info : collectSessions(tree.getFolder())) {
            if (info.getId() != null && !info.getId().isBlank()) {
                hosts.put(info.getId(), info);
            }
        }
        return hosts;
    }

    private List<SessionInfo> collectSessions(SessionFolder folder) {
        List<SessionInfo> sessions = new ArrayList<>();
        if (folder == null) {
            return sessions;
        }
        sessions.addAll(folder.getItems());
        for (SessionFolder child : folder.getFolders()) {
            sessions.addAll(collectSessions(child));
        }
        return sessions;
    }

    private String readRequiredSecret(PasswordStore passwordStore, String alias) throws LockedSecretsException {
        SecretReadResult result = passwordStore.getSecretWithoutPrompt(alias);
        if (result.getStatus() == SecretReadStatus.LOCKED) {
            throw new LockedSecretsException();
        }
        return result.getValue();
    }

    private void writeSecretQuietly(PasswordStore passwordStore, String alias, String value) {
        try {
            passwordStore.saveSecretWithoutPrompt(alias, value);
        } catch (Exception e) {
            log.error("Unable to save secret {}", alias, e);
        }
    }

    private Map<String, InfisicalAppState.HostSecretState> safeHostSecrets(InfisicalAppState state) {
        if (state == null || state.getHostSecrets() == null) {
            return Collections.emptyMap();
        }
        return state.getHostSecrets();
    }

    private Map<String, String> safeJumpPasswords(InfisicalAppState.HostSecretState hostSecret) {
        if (hostSecret.getJumpPasswords() == null) {
            return Collections.emptyMap();
        }
        return hostSecret.getJumpPasswords();
    }

    private List<ProviderRecord> safeProviders(InfisicalAppState state) {
        if (state == null || state.getProviders() == null) {
            return Collections.emptyList();
        }
        return state.getProviders();
    }

    private boolean isConfigured(Settings settings) {
        return settings != null
                && settings.getInfisicalBaseUrl() != null
                && !settings.getInfisicalBaseUrl().isBlank()
                && settings.getInfisicalProjectId() != null
                && !settings.getInfisicalProjectId().isBlank()
                && settings.getInfisicalEnvironment() != null
                && !settings.getInfisicalEnvironment().isBlank()
                && settings.getInfisicalClientId() != null
                && !settings.getInfisicalClientId().isBlank();
    }

    private String normalizeBasePath(String value) {
        String path = value == null || value.isBlank() ? "/vps-manager" : value.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private String readFileIfPresent(String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(file);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return null;
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Unable to read file {}", file, e);
            return null;
        }
    }

    private String readPublicKeyIfPresent(SessionInfo info) {
        if (info == null || info.getPrivateKeyFile() == null || info.getPrivateKeyFile().isBlank()) {
            return null;
        }
        return readFileIfPresent(info.getPrivateKeyFile() + ".pub");
    }

    private void writeFileIfMissing(String file, String content) {
        if (file == null || file.isBlank() || content == null || content.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(file);
            if (Files.exists(path)) {
                return;
            }
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path,
                              content,
                              StandardCharsets.UTF_8,
                              StandardOpenOption.CREATE_NEW,
                              StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.error("Unable to restore key file {}", file, e);
        }
    }

    private SavedSessionTree createDefaultTree() {
        SessionFolder rootFolder = new SessionFolder();
        rootFolder.setId(UUID.randomUUID().toString());
        rootFolder.setName("My sites");
        SavedSessionTree tree = new SavedSessionTree();
        tree.setFolder(rootFolder);
        return tree;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private void updateStatus(String statusText) {
        AppWindow window = App.getAppWindow();
        if (window == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> window.setInfisicalStatus(statusText));
    }

    private void updateLastSyncStatus() {
        updateStatus("Infisical: last sync " + STATUS_TIME_FORMAT.format(LocalDateTime.now()));
    }

    private static final class LockedSecretsException extends Exception {
    }
}
