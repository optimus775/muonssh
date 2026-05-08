package muon.app.ui.components.session;

import junit.framework.TestCase;
import muon.app.App;
import muon.app.common.settings.Settings;
import muon.app.vps.ProviderRecord;
import muon.app.vps.VpsProviderRepository;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;

public class SessionInfoPanelTest extends TestCase {

    public void testLoadingExistingSessionDoesNotRecalculateNextPaymentDate() throws Exception {
        Path configDir = Files.createTempDirectory("session-info-panel-test");
        App.getCONTEXT().setConfigDir(configDir.toFile());
        App.getCONTEXT().setSettings(new Settings());
        App.getCONTEXT().updateSkin();
        UIManager.put("defaultFont", new Font("Dialog", Font.PLAIN, 12));
        UIManager.put("iconFont", new Font("Dialog", Font.PLAIN, 12));

        SessionInfo info = new SessionInfo();
        info.setHost("192.0.2.55");
        info.setUser("root");
        info.setBillingPeriodType("fixed_period");
        info.setBillingCycle("monthly");
        info.setNextPaymentDate("2030-12-19");

        SessionInfoPanel panel = new SessionInfoPanel();
        panel.setSessionInfo(info);

        assertEquals("2030-12-19", info.getNextPaymentDate());
    }

    public void testNewServerDoesNotPreselectProvider() throws Exception {
        Path configDir = Files.createTempDirectory("session-info-panel-provider-test");
        App.getCONTEXT().setConfigDir(configDir.toFile());
        App.getCONTEXT().setSettings(new Settings());
        App.getCONTEXT().updateSkin();
        UIManager.put("defaultFont", new Font("Dialog", Font.PLAIN, 12));
        UIManager.put("iconFont", new Font("Dialog", Font.PLAIN, 12));

        ProviderRecord provider = new ProviderRecord();
        provider.setName("Provider 1");
        new VpsProviderRepository().upsertProvider(provider);

        SessionInfo info = new SessionInfo();
        info.setHost("192.0.2.56");
        info.setUser("root");

        SessionInfoPanel panel = new SessionInfoPanel();
        panel.setSessionInfo(info);

        Field providerField = SessionInfoPanel.class.getDeclaredField("cmbProvider");
        providerField.setAccessible(true);
        @SuppressWarnings("unchecked")
        JComboBox<ProviderRecord> comboBox = (JComboBox<ProviderRecord>) providerField.get(panel);

        assertEquals(0, comboBox.getSelectedIndex());
        assertNull(info.getProviderId());
        assertNull(info.getProvider());
    }
}
