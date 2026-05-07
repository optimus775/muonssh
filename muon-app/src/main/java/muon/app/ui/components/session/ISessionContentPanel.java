package muon.app.ui.components.session;

import muon.app.ssh.RemoteSessionInstance;

public interface ISessionContentPanel {
    void close();

    default void closeForShutdown() {
        close();
    }

    int getActiveSessionId();

    default RemoteSessionInstance getRemoteSessionInstance() {
        return null;
    }

    SessionInfo getInfo();
}
