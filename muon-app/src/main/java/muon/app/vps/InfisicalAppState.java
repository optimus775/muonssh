package muon.app.vps;

import lombok.Getter;
import lombok.Setter;
import muon.app.ui.components.session.SavedSessionTree;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class InfisicalAppState {
    private int schema;
    private long updatedAt;
    private String lastSelection;
    private SavedSessionTree tree;
    private List<ProviderRecord> providers;
    private Map<String, HostSecretState> hostSecrets = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class HostSecretState {
        private String sshPassword;
        private String proxyPassword;
        private Map<String, String> jumpPasswords = new LinkedHashMap<>();
        private String privateKeyPath;
        private String privateKeyContent;
        private String publicKeyContent;
    }
}
