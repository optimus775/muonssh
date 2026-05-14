package muon.app.common.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import muon.app.App;
import muon.app.util.Constants;

import java.io.File;
import java.io.IOException;

@Slf4j
public class SettingsManager {

    private final File configDir;
    private final ObjectMapper objectMapper;

    public SettingsManager(File configDir) {
        this.configDir = configDir;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public synchronized Settings loadSettings() {
        File file = new File(configDir, Constants.CONFIG_DB_FILE);
        if (file.exists()) {
            try {
                Settings settings = objectMapper.readValue(file, new TypeReference<>() {
                });
                if (settings.normalizeTerminalPalette()) {
                    saveMigratedSettings(file, settings);
                }
                return settings;
            } catch (IOException e) {
                log.error("Error reading settings: {}", e.getMessage(), e);
            }
        }
        return new Settings();
    }

    public synchronized void saveSettings() {
        File file = new File(configDir, Constants.CONFIG_DB_FILE);
        try {
            objectMapper.writeValue(file, App.getGlobalSettings());
        } catch (IOException e) {
            log.error("Error saving settings: {}", e.getMessage(), e);
        }
    }

    private void saveMigratedSettings(File file, Settings settings) {
        try {
            objectMapper.writeValue(file, settings);
        } catch (IOException e) {
            log.error("Error saving migrated settings: {}", e.getMessage(), e);
        }
    }
}
