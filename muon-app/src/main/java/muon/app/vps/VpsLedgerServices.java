package muon.app.vps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import muon.app.App;
import muon.app.common.PasswordStore;
import muon.app.common.settings.Settings;
import muon.app.ui.components.session.SavedSessionTree;
import muon.app.ui.components.session.SessionFolder;
import muon.app.ui.components.session.SessionInfo;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
public final class VpsLedgerServices {

    private static final VpsHostRepository HOST_REPOSITORY = new VpsHostRepository();
    private static final VpsProviderRepository PROVIDER_REPOSITORY = new VpsProviderRepository();
    private static final VikunjaClient VIKUNJA_CLIENT = new VikunjaClient();
    private static final InfisicalClient INFISICAL_CLIENT = new InfisicalClient();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private VpsLedgerServices() {
    }

    public static void syncVikunjaPaymentsAsync(SessionFolder folder, String lastSelectionPath) {
        Settings settings = App.getGlobalSettings();
        if (!isVikunjaConfigured(settings)) {
            return;
        }
        List<SessionInfo> sessions = collectSessions(folder);
        App.getCONTEXT().getExecutor().submit(() -> {
            try {
                String token = PasswordStore.getSharedInstance().getSecret(VikunjaClient.API_TOKEN_ALIAS);
                if (token == null || token.isBlank()) {
                    return;
                }
                for (SessionInfo info : sessions) {
                    if (!hasVikunjaSchedule(info)) {
                        continue;
                    }
                    try {
                        Long previousTaskId = info.getVikunjaTaskId();
                        applyProviderDetails(info);
                        long taskId = VIKUNJA_CLIENT.upsertPaymentTask(info, settings, token);
                        if (!Objects.equals(previousTaskId, taskId)) {
                            info.setVikunjaTaskId(taskId);
                            info.setUpdatedAt(System.currentTimeMillis());
                            HOST_REPOSITORY.upsertHostOnly(info);
                        }
                    } catch (Exception e) {
                        log.error("Unable to sync Vikunja payment task for {}", info.getName(), e);
                    }
                }
            } catch (Exception e) {
                log.error("Unable to sync Vikunja payment tasks", e);
            }
        });
    }

    public static void syncVikunjaNow(Component parent, SessionInfo info) {
        Settings settings = App.getGlobalSettings();
        if (!isVikunjaConfigured(settings)) {
            showMessage(parent, "Configure Vikunja base URL, project ID and API token first.", JOptionPane.WARNING_MESSAGE);
            return;
        }
        App.getCONTEXT().getExecutor().submit(() -> {
            try {
                String token = PasswordStore.getSharedInstance().getSecret(VikunjaClient.API_TOKEN_ALIAS);
                if (token == null || token.isBlank()) {
                    throw new IllegalStateException("Vikunja API token is empty");
                }
                if (!hasVikunjaSchedule(info)) {
                    throw new IllegalStateException("Set a payment date or balance check date first");
                }
                applyProviderDetails(info);
                long taskId = VIKUNJA_CLIENT.upsertPaymentTask(info, settings, token);
                info.setVikunjaTaskId(taskId);
                info.setUpdatedAt(System.currentTimeMillis());
                HOST_REPOSITORY.upsertHostOnly(info);
                showMessage(parent, "Vikunja task synced: " + taskId, JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                log.error("Unable to sync Vikunja task", e);
                showMessage(parent, "Unable to sync Vikunja task: " + e.getMessage(), JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    public static void syncInfisicalNow(Component parent) {
        Settings settings = App.getGlobalSettings();
        if (!isInfisicalConfigured(settings)) {
            showMessage(parent, "Configure Infisical project, environment, client ID and client secret first.", JOptionPane.WARNING_MESSAGE);
            return;
        }
        App.getCONTEXT().getExecutor().submit(() -> {
            try {
                String clientSecret = PasswordStore.getSharedInstance().getSecret(InfisicalClient.CLIENT_SECRET_ALIAS);
                if (clientSecret == null || clientSecret.isBlank()) {
                    throw new IllegalStateException("Infisical client secret is empty");
                }
                String token = INFISICAL_CLIENT.login(settings, clientSecret);
                mergeRemoteInfisicalState(settings, token);
                List<SessionInfo> hosts = HOST_REPOSITORY.listHosts();
                pushInfisicalState(settings, token, hosts);
                showMessage(parent, "Infisical sync completed for " + hosts.size() + " hosts.", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                log.error("Unable to sync Infisical state", e);
                showMessage(parent, "Unable to sync Infisical state: " + e.getMessage(), JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private static void pushInfisicalState(Settings settings, String token, List<SessionInfo> hosts) throws Exception {
        String basePath = normalizeBasePath(settings.getInfisicalSecretBasePath());
        List<ProviderRecord> providers = PROVIDER_REPOSITORY.listProviders();
        INFISICAL_CLIENT.createOrUpdateSecret(settings, token, basePath + "/index", "HOSTS_JSON", buildIndexJson(hosts));
        INFISICAL_CLIENT.createOrUpdateSecret(settings, token, basePath + "/providers", "PROVIDERS_JSON", buildProvidersJson(providers));

        for (SessionInfo info : hosts) {
            String hostPath = basePath + "/hosts/" + info.getId();
            INFISICAL_CLIENT.createOrUpdateSecret(settings, token, hostPath, "HOST_JSON", HOST_REPOSITORY.toHostJson(info));
            if (settings.isInfisicalSyncPrivateKeys() && info.isSyncPrivateKey()) {
                String privateKey = readFileIfPresent(info.getPrivateKeyFile());
                if (privateKey != null) {
                    INFISICAL_CLIENT.createOrUpdateSecret(settings, token, hostPath, "SSH_PRIVATE_KEY", privateKey);
                }
            }
            if (settings.isInfisicalSyncPrivateKeys() && info.isSyncPublicKey()) {
                String publicKey = readPublicKeyIfPresent(info);
                if (publicKey != null) {
                    INFISICAL_CLIENT.createOrUpdateSecret(settings, token, hostPath, "SSH_PUBLIC_KEY", publicKey);
                }
            }
        }
    }

    private static void mergeRemoteInfisicalState(Settings settings, String token) throws Exception {
        String basePath = normalizeBasePath(settings.getInfisicalSecretBasePath());
        mergeRemoteProviders(settings, token, basePath);
        String indexJson = INFISICAL_CLIENT.readSecret(settings, token, basePath + "/index", "HOSTS_JSON");
        if (indexJson == null || indexJson.isBlank()) {
            return;
        }

        SavedSessionTree tree = HOST_REPOSITORY.loadTree();
        Map<String, SessionInfo> localHosts = new HashMap<>();
        collectSessions(tree.getFolder()).forEach(info -> localHosts.put(info.getId(), info));

        boolean changed = false;
        for (var node : OBJECT_MAPPER.readTree(indexJson).path("hosts")) {
            String hostId = node.path("id").asText(null);
            if (hostId == null || hostId.isBlank()) {
                continue;
            }
            String remoteJson = INFISICAL_CLIENT.readSecret(settings, token, basePath + "/hosts/" + hostId, "HOST_JSON");
            if (remoteJson == null || remoteJson.isBlank()) {
                continue;
            }
            SessionInfo remote = HOST_REPOSITORY.fromHostJson(remoteJson);
            SessionInfo local = localHosts.get(remote.getId());
            if (local == null) {
                getDefaultFolder(tree.getFolder()).getItems().add(remote);
                changed = true;
                continue;
            }

            String localJson = HOST_REPOSITORY.toHostJson(local);
            if (remote.getUpdatedAt() > local.getUpdatedAt()) {
                HOST_REPOSITORY.recordConflict(remote.getId(), localJson, remoteJson);
                copySession(remote, local);
                changed = true;
            } else if (remote.getUpdatedAt() < local.getUpdatedAt()) {
                HOST_REPOSITORY.recordConflict(remote.getId(), localJson, remoteJson);
            }
        }

        if (changed) {
            HOST_REPOSITORY.saveTree(tree.getFolder(), tree.getLastSelection());
        }
    }

    private static SessionFolder getDefaultFolder(SessionFolder root) {
        if (root != null && "Empty_Root".equals(root.getName()) && !root.getFolders().isEmpty()) {
            return root.getFolders().get(0);
        }
        return root;
    }

    private static void copySession(SessionInfo source, SessionInfo target) {
        target.setHost(source.getHost());
        target.setUser(source.getUser());
        target.setLocalFolder(source.getLocalFolder());
        target.setRemoteFolder(source.getRemoteFolder());
        target.setPort(source.getPort());
        target.setFavouriteRemoteFolders(source.getFavouriteRemoteFolders());
        target.setFavouriteLocalFolders(source.getFavouriteLocalFolders());
        target.setPrivateKeyFile(source.getPrivateKeyFile());
        target.setProxyPort(source.getProxyPort());
        target.setProxyHost(source.getProxyHost());
        target.setProxyUser(source.getProxyUser());
        target.setProxyPassword(source.getProxyPassword());
        target.setProxyType(source.getProxyType());
        target.setUseJumpHosts(source.isUseJumpHosts());
        target.setJumpType(source.getJumpType());
        target.setJumpHosts(source.getJumpHosts());
        target.setPortForwardingRules(source.getPortForwardingRules());
        target.setUseX11Forwarding(source.isUseX11Forwarding());
        target.setSftpOnly(source.isSftpOnly());
        target.setName(source.getName());
        target.setProviderId(source.getProviderId());
        target.setProvider(source.getProvider());
        target.setProviderUrl(source.getProviderUrl());
        target.setAccountId(source.getAccountId());
        target.setBillingPeriodType(source.getBillingPeriodType());
        target.setBillingCycle(source.getBillingCycle());
        target.setBillingCycleDays(source.getBillingCycleDays());
        target.setBillingPeriodDays(source.getBillingPeriodDays());
        target.setPrice(source.getPrice());
        target.setCurrency(source.getCurrency());
        target.setNextPaymentDate(source.getNextPaymentDate());
        target.setHourlyRate(source.getHourlyRate());
        target.setNextBalanceCheckDate(source.getNextBalanceCheckDate());
        target.setCancelByDate(source.getCancelByDate());
        target.setAutoPay(source.isAutoPay());
        target.setVpsStatus(source.getVpsStatus());
        target.setTags(source.getTags());
        target.setDescription(source.getDescription());
        target.setExternalRefs(source.getExternalRefs());
        target.setVikunjaTaskId(source.getVikunjaTaskId());
        target.setSyncPrivateKey(source.isSyncPrivateKey());
        target.setSyncPublicKey(source.isSyncPublicKey());
        target.setUpdatedAt(source.getUpdatedAt());
    }

    private static String buildIndexJson(List<SessionInfo> hosts) throws IOException {
        ObjectNode index = OBJECT_MAPPER.createObjectNode();
        index.put("schema", VpsDatabaseManager.getCurrentSchema());
        index.put("updated_at", System.currentTimeMillis());
        ArrayNode hostArray = OBJECT_MAPPER.createArrayNode();
        for (SessionInfo info : hosts) {
            ObjectNode item = OBJECT_MAPPER.createObjectNode();
            item.put("id", info.getId());
            item.put("name", info.getName());
            item.put("host", info.getHost());
            item.put("provider_id", info.getProviderId());
            item.put("status", info.getVpsStatus());
            item.put("updated_at", info.getUpdatedAt());
            hostArray.add(item);
        }
        index.set("hosts", hostArray);
        return OBJECT_MAPPER.writeValueAsString(index);
    }

    private static String buildProvidersJson(List<ProviderRecord> providers) throws IOException {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("schema", VpsDatabaseManager.getCurrentSchema());
        root.put("updated_at", System.currentTimeMillis());
        root.set("providers", OBJECT_MAPPER.valueToTree(providers));
        return OBJECT_MAPPER.writeValueAsString(root);
    }

    private static void mergeRemoteProviders(Settings settings, String token, String basePath) throws Exception {
        String providersJson = INFISICAL_CLIENT.readSecret(settings, token, basePath + "/providers", "PROVIDERS_JSON");
        if (providersJson == null || providersJson.isBlank()) {
            return;
        }
        Map<String, ProviderRecord> localProviders = new HashMap<>();
        for (ProviderRecord provider : PROVIDER_REPOSITORY.listProviders()) {
            localProviders.put(provider.getId(), provider);
        }
        for (var node : OBJECT_MAPPER.readTree(providersJson).path("providers")) {
            ProviderRecord remote = OBJECT_MAPPER.treeToValue(node, ProviderRecord.class);
            if (remote == null || remote.getId() == null || remote.getName() == null || remote.getName().isBlank()) {
                continue;
            }
            ProviderRecord local = localProviders.get(remote.getId());
            if (local == null || remote.getUpdatedAt() > local.getUpdatedAt()) {
                PROVIDER_REPOSITORY.upsertProvider(remote);
            }
        }
    }

    private static String readPublicKeyIfPresent(SessionInfo info) {
        if (info.getPrivateKeyFile() == null || info.getPrivateKeyFile().isBlank()) {
            return null;
        }
        return readFileIfPresent(info.getPrivateKeyFile() + ".pub");
    }

    private static String readFileIfPresent(String file) {
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
            log.error("Unable to read key file {}", file, e);
            return null;
        }
    }

    private static boolean isVikunjaConfigured(Settings settings) {
        return settings != null
                && settings.getVikunjaBaseUrl() != null
                && !settings.getVikunjaBaseUrl().isBlank()
                && settings.getVikunjaProjectId() > 0;
    }

    private static boolean hasVikunjaSchedule(SessionInfo info) {
        if (info == null) {
            return false;
        }
        if ("hourly_balance".equals(info.getBillingPeriodType())) {
            return info.getNextBalanceCheckDate() != null && !info.getNextBalanceCheckDate().isBlank();
        }
        return info.getNextPaymentDate() != null && !info.getNextPaymentDate().isBlank();
    }

    private static void applyProviderDetails(SessionInfo info) {
        ProviderRecord provider = PROVIDER_REPOSITORY.findProvider(info.getProviderId());
        if (provider == null) {
            return;
        }
        info.setProvider(provider.getName());
        String providerUrl = firstNonBlank(provider.getBillingUrl(), provider.getWebsite());
        if (providerUrl != null) {
            info.setProviderUrl(providerUrl);
        }
        if ((info.getAccountId() == null || info.getAccountId().isBlank()) && provider.getAccountId() != null) {
            info.setAccountId(provider.getAccountId());
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static boolean isInfisicalConfigured(Settings settings) {
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

    private static List<SessionInfo> collectSessions(SessionFolder folder) {
        List<SessionInfo> result = new ArrayList<>();
        if (folder == null) {
            return result;
        }
        result.addAll(folder.getItems());
        for (SessionFolder child : folder.getFolders()) {
            result.addAll(collectSessions(child));
        }
        return result;
    }

    private static String normalizeBasePath(String value) {
        String path = value == null || value.isBlank() ? "/vps-manager" : value.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static void showMessage(Component parent, String message, int type) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent, message, "VPS Ledger", type));
    }
}
