package muon.app.vps;

import lombok.Getter;
import lombok.Setter;
import muon.app.ui.components.session.SessionInfo;

@Getter
@Setter
public class HostRecord {
    private SessionInfo sessionInfo;
    private boolean deleted;
    private long updatedAt;

    public HostRecord() {
    }

    public HostRecord(SessionInfo sessionInfo) {
        this.sessionInfo = sessionInfo;
        this.updatedAt = sessionInfo == null ? System.currentTimeMillis() : sessionInfo.getUpdatedAt();
    }

    public String getId() {
        return sessionInfo == null ? null : sessionInfo.getId();
    }

    public String getName() {
        return sessionInfo == null ? null : sessionInfo.getName();
    }
}
