package muon.app.vps;

import junit.framework.TestCase;
import muon.app.App;
import muon.app.common.PasswordStore;
import muon.app.common.settings.Settings;
import muon.app.ui.components.session.HopEntry;
import muon.app.ui.components.session.SavedSessionTree;
import muon.app.ui.components.session.SessionFolder;
import muon.app.ui.components.session.SessionInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class InfisicalSyncServiceTest extends TestCase {

    public void testBuildLocalStateIncludesSecretsAndKeyContents() throws Exception {
        Path configDir = Files.createTempDirectory("infisical-sync-build");
        App.getCONTEXT().setConfigDir(configDir.toFile());
        App.getCONTEXT().setSettings(new Settings());

        Path keyDir = Files.createDirectories(configDir.resolve("keys"));
        Path privateKey = keyDir.resolve("id_test");
        Files.writeString(privateKey, "PRIVATE KEY");
        Files.writeString(keyDir.resolve("id_test.pub"), "PUBLIC KEY");

        ProviderRecord provider = new ProviderRecord();
        provider.setName("Provider One");
        provider = new VpsProviderRepository().upsertProvider(provider);

        SessionInfo info = new SessionInfo();
        info.setId("host-1");
        info.setName("server-1");
        info.setHost("192.0.2.20");
        info.setUser("root");
        info.setProviderId(provider.getId());
        info.setPrivateKeyFile(privateKey.toString());
        HopEntry hop = new HopEntry();
        hop.setId("hop-1");
        hop.setHost("192.0.2.21");
        hop.setUser("jump");
        info.getJumpHosts().add(hop);

        SessionFolder folder = new SessionFolder();
        folder.setId("folder-1");
        folder.setName("My sites");
        folder.getItems().add(info);

        VpsHostRepository repository = new VpsHostRepository();
        repository.saveTree(folder, info.getId());

        PasswordStore passwordStore = PasswordStore.getSharedInstance();
        passwordStore.setFallbackDecisionProvider(name -> false);
        passwordStore.saveSecret("session:host-1:ssh-password", "ssh-secret");
        passwordStore.saveSecret("session:host-1:proxy-password", "proxy-secret");
        passwordStore.saveSecret("session:host-1:jump:hop-1:password", "jump-secret");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            InfisicalSyncService service = new InfisicalSyncService(
                    new VpsHostRepository(),
                    new VpsProviderRepository(),
                    new InfisicalClient(),
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    scheduler);

            InfisicalAppState state = service.buildLocalState(1234L);

            assertEquals(1234L, state.getUpdatedAt());
            assertEquals(info.getId(), state.getLastSelection());
            assertEquals(1, state.getProviders().size());
            assertEquals("Provider One", state.getProviders().get(0).getName());
            assertEquals(1, state.getTree().getFolder().getItems().size());
            assertNull(state.getTree().getFolder().getItems().get(0).getPassword());

            InfisicalAppState.HostSecretState hostSecret = state.getHostSecrets().get("host-1");
            assertNotNull(hostSecret);
            assertEquals("ssh-secret", hostSecret.getSshPassword());
            assertEquals("proxy-secret", hostSecret.getProxyPassword());
            assertEquals("jump-secret", hostSecret.getJumpPasswords().get("hop-1"));
            assertEquals(privateKey.toString(), hostSecret.getPrivateKeyPath());
            assertEquals("PRIVATE KEY", hostSecret.getPrivateKeyContent());
            assertEquals("PUBLIC KEY", hostSecret.getPublicKeyContent());
        } finally {
            scheduler.shutdownNow();
        }
    }

    public void testApplyRemoteStateReplacesLocalSnapshotAndRestoresKeyFiles() throws Exception {
        Path configDir = Files.createTempDirectory("infisical-sync-apply");
        App.getCONTEXT().setConfigDir(configDir.toFile());
        App.getCONTEXT().setSettings(new Settings());

        SessionInfo oldInfo = new SessionInfo();
        oldInfo.setId("old-host");
        oldInfo.setName("old-server");
        oldInfo.setHost("192.0.2.30");
        oldInfo.setUser("root");
        HopEntry oldHop = new HopEntry();
        oldHop.setId("old-hop");
        oldHop.setHost("192.0.2.31");
        oldHop.setUser("jump");
        oldInfo.getJumpHosts().add(oldHop);

        SessionFolder oldFolder = new SessionFolder();
        oldFolder.setId("old-folder");
        oldFolder.setName("My sites");
        oldFolder.getItems().add(oldInfo);

        VpsHostRepository repository = new VpsHostRepository();
        repository.saveTree(oldFolder, oldInfo.getId());

        PasswordStore passwordStore = PasswordStore.getSharedInstance();
        passwordStore.setFallbackDecisionProvider(name -> false);
        passwordStore.saveSecret("session:old-host:ssh-password", "old-ssh");
        passwordStore.saveSecret("session:old-host:proxy-password", "old-proxy");
        passwordStore.saveSecret("session:old-host:jump:old-hop:password", "old-jump");

        SessionInfo remoteInfo = new SessionInfo();
        remoteInfo.setId("remote-host");
        remoteInfo.setName("remote-server");
        remoteInfo.setHost("192.0.2.40");
        remoteInfo.setUser("admin");
        HopEntry remoteHop = new HopEntry();
        remoteHop.setId("remote-hop");
        remoteHop.setHost("192.0.2.41");
        remoteHop.setUser("relay");
        remoteInfo.getJumpHosts().add(remoteHop);

        SessionFolder remoteFolder = new SessionFolder();
        remoteFolder.setId("remote-folder");
        remoteFolder.setName("My sites");
        remoteFolder.getItems().add(remoteInfo);

        SavedSessionTree remoteTree = new SavedSessionTree();
        remoteTree.setFolder(remoteFolder);
        remoteTree.setLastSelection("remote-host");

        ProviderRecord remoteProvider = new ProviderRecord();
        remoteProvider.setId("provider-remote");
        remoteProvider.setName("Remote Provider");
        remoteProvider.setUpdatedAt(200L);

        Path remoteKeyPath = configDir.resolve("restored").resolve("id_remote");
        InfisicalAppState.HostSecretState remoteSecrets = new InfisicalAppState.HostSecretState();
        remoteSecrets.setSshPassword("remote-ssh");
        remoteSecrets.setProxyPassword("remote-proxy");
        remoteSecrets.setJumpPasswords(Map.of("remote-hop", "remote-jump"));
        remoteSecrets.setPrivateKeyPath(remoteKeyPath.toString());
        remoteSecrets.setPrivateKeyContent("REMOTE PRIVATE");
        remoteSecrets.setPublicKeyContent("REMOTE PUBLIC");

        InfisicalAppState remoteState = new InfisicalAppState();
        remoteState.setSchema(VpsDatabaseManager.getCurrentSchema());
        remoteState.setUpdatedAt(200L);
        remoteState.setLastSelection("remote-host");
        remoteState.setTree(remoteTree);
        remoteState.setProviders(List.of(remoteProvider));
        remoteState.setHostSecrets(Map.of("remote-host", remoteSecrets));

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            InfisicalSyncService service = new InfisicalSyncService(
                    new VpsHostRepository(),
                    new VpsProviderRepository(),
                    new InfisicalClient(),
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    scheduler);

            service.applyRemoteState(remoteState);

            SavedSessionTree loaded = repository.loadTree();
            assertEquals("remote-host", loaded.getLastSelection());
            assertEquals(1, loaded.getFolder().getItems().size());
            SessionInfo loadedInfo = loaded.getFolder().getItems().get(0);
            assertEquals("remote-host", loadedInfo.getId());
            assertEquals(remoteKeyPath.toString(), loadedInfo.getPrivateKeyFile());

            List<ProviderRecord> providers = new VpsProviderRepository().listProviders();
            assertEquals(1, providers.size());
            assertEquals("Remote Provider", providers.get(0).getName());

            assertEquals("200", repository.getAppStateValue(InfisicalSyncService.LOCAL_STATE_UPDATED_AT_KEY));
            assertEquals("remote-ssh",
                         passwordStore.getSecretWithoutPrompt("session:remote-host:ssh-password").getValue());
            assertEquals("remote-proxy",
                         passwordStore.getSecretWithoutPrompt("session:remote-host:proxy-password").getValue());
            assertEquals("remote-jump",
                         passwordStore.getSecretWithoutPrompt("session:remote-host:jump:remote-hop:password").getValue());
            assertEquals(PasswordStore.SecretReadStatus.MISSING,
                         passwordStore.getSecretWithoutPrompt("session:old-host:ssh-password").getStatus());

            assertEquals("REMOTE PRIVATE", Files.readString(remoteKeyPath));
            assertEquals("REMOTE PUBLIC", Files.readString(Path.of(remoteKeyPath + ".pub")));
        } finally {
            scheduler.shutdownNow();
        }
    }
}
