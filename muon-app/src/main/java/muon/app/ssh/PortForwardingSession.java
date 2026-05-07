package muon.app.ssh;

import lombok.extern.slf4j.Slf4j;
import muon.app.ui.components.session.PortForwardingRule;
import muon.app.ui.components.session.SessionContentPanel;
import muon.app.ui.components.session.SessionInfo;
import muon.app.util.enums.PortForwardingType;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Parameters;
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder.Forward;
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener;
import net.schmizz.sshj.transport.TransportException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class PortForwardingSession {
    private static final long CLOSE_TIMEOUT_SECONDS = 5;
    private final SSHHandler ssh;
    private final SessionInfo info;
    private final ExecutorService threadPool = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "Port-Forwarding");
        thread.setDaemon(true);
        return thread;
    });
    private final List<ServerSocket> ssList = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public PortForwardingSession(SessionInfo info,
                                 CachedCredentialProvider cachedCredentialProvider,
                                 SessionContentPanel sessionContentPanel) {
        this.info = info;
        this.ssh = new SSHHandler(info, cachedCredentialProvider, sessionContentPanel);
    }

    public void close() {
        close(false);
    }

    public void closeAndWait() {
        close(true);
    }

    private void close(boolean waitForCleanup) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeResources();
        this.threadPool.shutdownNow();
        if (waitForCleanup) {
            try {
                if (!this.threadPool.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("Timed out waiting for port forwarding shutdown");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error(e.getMessage(), e);
            }
        }
    }

    private void closeResources() {
        try {
            this.ssh.close();
        } catch (Exception e) {
            log.error("Failed to close ssh", e);
        }
        for (ServerSocket ss : ssList) {
            try {
                ss.close();
            } catch (Exception e2) {
                log.error("Failed to close ss", e2);
            }
        }
    }

    public void start() {
        this.threadPool.submit(this::forwardPorts);
    }

    private void forwardPorts() {
        try {
            if (!ssh.isConnected()) {
                ssh.connect();
            }
            for (PortForwardingRule r : info.getPortForwardingRules()) {
                try {
                    if (r.getType() == PortForwardingType.LOCAL) {
                        forwardLocalPort(r);
                    } else if (r.getType() == PortForwardingType.REMOTE) {
                        forwardRemotePort(r);
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private void forwardLocalPort(PortForwardingRule r) throws Exception {
        ServerSocket ss = new ServerSocket();
        ssList.add(ss);
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(r.getBindHost(), r.getSourcePort()));
        this.threadPool.submit(() -> {
            try {
                this.ssh.newLocalPortForwarder(
                                new Parameters(r.getBindHost(), r.getSourcePort(), r.getHost(), r.getTargetPort()), ss)
                        .listen();
            } catch (IOException e) {
                log.error(e.getMessage(), e);
            }
        });
    }

    private void forwardRemotePort(PortForwardingRule r) {
        this.threadPool.submit(() -> {
            /*
             * We make _server_ listen on port 8080, which forwards all connections to us as
             * a channel, and we further forward all such channels to google.com:80
             */
            try {
                ssh.getRemotePortForwarder().bind(
                        // where the server should listen
                        new Forward(r.getSourcePort()),
                        // what we do with incoming connections that are forwarded to us
                        new SocketForwardingConnectListener(new InetSocketAddress(r.getHost(), r.getTargetPort())));

                // Something to hang on to so that the forwarding stays
                ssh.getTransport().join();
            } catch (ConnectionException | TransportException e) {

                log.error(e.getMessage(), e);
            }
        });
    }
}
