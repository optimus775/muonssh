package muon.app.vps;

import junit.framework.TestCase;
import muon.app.App;
import muon.app.ui.components.session.SavedSessionTree;
import muon.app.ui.components.session.SessionFolder;
import muon.app.ui.components.session.SessionInfo;

import java.nio.file.Files;
import java.nio.file.Path;

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
        provider.setBillingUrl("https://provider.example/billing");
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
        assertEquals("https://provider.example/billing", loadedInfo.getProviderUrl());
        assertEquals("hourly_balance", loadedInfo.getBillingPeriodType());
        assertEquals("0.01", loadedInfo.getHourlyRate());
        assertEquals("2026-06-10", loadedInfo.getNextBalanceCheckDate());
    }
}
