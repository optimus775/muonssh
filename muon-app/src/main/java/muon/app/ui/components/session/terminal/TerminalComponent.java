package muon.app.ui.components.session.terminal;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.ui.JediTermWidget;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import muon.app.App;
import muon.app.ui.components.common.ClosableTabContent;
import muon.app.ui.components.common.ClosableTabbedPanel.TabTitle;
import muon.app.ui.components.session.SessionContentPanel;
import muon.app.ui.components.session.SessionInfo;
import muon.app.ui.components.session.terminal.ssh.DisposableTtyConnector;
import muon.app.ui.components.session.terminal.ssh.SshTtyConnector;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static muon.app.util.ScalingUtil.getScaledEmptyBorder;

@Slf4j
public class TerminalComponent extends JPanel implements ClosableTabContent {
    private static final int[] RECONNECT_DELAYS_SEC = { 2, 5, 10, 20 };
    private static final int RECONNECT_VERIFY_DELAY_SEC = 5;
    private final JPanel contentPane;

    @Getter
    private final JediTermWidget term = new CustomJediterm(new CustomizedSettingsProvider());
    private DisposableTtyConnector tty;
    private String name;
    private final SessionInfo info;
    private final String initialCommand;
    private final SessionContentPanel sessionContentPanel;
    private final Box reconnectionBox;
    private final JLabel reconnectLabel;
    private final JButton btnReconnect;
    private final ScheduledExecutorService reconnectExecutor;
    private volatile ScheduledFuture<?> reconnectFuture;
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @Getter
    private final TabTitle tabTitle;

    public TerminalComponent(SessionInfo info, String name, String command, SessionContentPanel sessionContentPanel) {
        setLayout(new BorderLayout());
        log.debug("Current terminal font: {}", App.getGlobalSettings().getTerminalFontName());
        this.name = name;
        this.info = info;
        this.initialCommand = command;
        this.sessionContentPanel = sessionContentPanel;
        this.tabTitle = new TabTitle();
        contentPane = new JPanel(new BorderLayout());
        JRootPane rootPane = new JRootPane();
        rootPane.setContentPane(contentPane);
        add(rootPane);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                log.debug("Requesting focus");
                term.requestFocusInWindow();
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                log.info("Hiding focus");
            }
        });

        tty = new SshTtyConnector(info, command, sessionContentPanel);
        reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Terminal-Reconnect-" + name);
            thread.setDaemon(true);
            return thread;
        });

        reconnectionBox = Box.createHorizontalBox();
        reconnectionBox.setOpaque(true);
        reconnectionBox.setBackground(Color.RED);
        reconnectLabel = new JLabel("Session not connected");
        reconnectionBox.add(reconnectLabel);
        btnReconnect = new JButton("Reconnect");
        btnReconnect.addActionListener(e -> {
            reconnectAttempt.set(0);
            doReconnect();
        });
        reconnectionBox.add(Box.createHorizontalGlue());
        reconnectionBox.add(btnReconnect);
        reconnectionBox.setBorder(getScaledEmptyBorder(10, 10, 10, 10));
        term.addListener(e -> {
            log.info("Disconnected");
            SwingUtilities.invokeLater(() -> {
                showReconnectBanner("Session not connected");
            });
            scheduleAutoReconnect();
        });
        term.setTtyConnector(tty);
        contentPane.add(term);

    }

    @Override
    public String toString() {
        return "Terminal " + this.name;
    }

    @Override
    public boolean close() {
        log.info("Closing terminal...{}", name);
        closed.set(true);
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
        }
        reconnectExecutor.shutdownNow();
        this.term.close();
        return true;
    }

    public void sendCommand(String command) {
        ((CustomJediterm) this.term).sendCommand(command);
    }

    public void start() {
        syncTtySize();
        term.start();
    }

    private void syncTtySize() {
        TermSize size = term.getTerminalPanel().getTerminalSizeFromComponent();
        if (tty != null && size != null) {
            tty.resize(JediTerminal.ensureTermMinimumSize(size));
        }
    }

    private void scheduleAutoReconnect() {
        if (closed.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        int attempt = reconnectAttempt.getAndIncrement();
        int delaySec = RECONNECT_DELAYS_SEC[Math.min(attempt, RECONNECT_DELAYS_SEC.length - 1)];
        SwingUtilities
                .invokeLater(() -> showReconnectBanner("Session not connected. Reconnecting in " + delaySec + "s"));
        reconnectFuture = reconnectExecutor.schedule(() -> SwingUtilities.invokeLater(this::doReconnect), delaySec,
                TimeUnit.SECONDS);
    }

    private void scheduleReconnectCheck(DisposableTtyConnector checkedTty) {
        reconnectFuture = reconnectExecutor.schedule(() -> {
            if (closed.get()) {
                return;
            }
            if (checkedTty != tty) {
                return;
            }
            if (isConnectorUsable(checkedTty)) {
                reconnectAttempt.set(0);
                reconnectScheduled.set(false);
                SwingUtilities.invokeLater(this::hideReconnectBanner);
            } else if (term.isSessionRunning() && !checkedTty.isCancelled()) {
                scheduleReconnectCheck(checkedTty);
            } else {
                reconnectScheduled.set(false);
                scheduleAutoReconnect();
            }
        }, RECONNECT_VERIFY_DELAY_SEC, TimeUnit.SECONDS);
    }

    private void doReconnect() {
        if (closed.get()) {
            return;
        }
        if (isConnectorUsable(tty)) {
            reconnectAttempt.set(0);
            reconnectScheduled.set(false);
            hideReconnectBanner();
            return;
        }
        if (term.isSessionRunning() && tty != null && !tty.isCancelled()) {
            reconnectScheduled.set(true);
            showReconnectBanner("Reconnecting...");
            scheduleReconnectCheck(tty);
            return;
        }
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
        }
        reconnectScheduled.set(true);
        showReconnectBanner("Reconnecting...");
        DisposableTtyConnector reconnectTty = new SshTtyConnector(info, initialCommand, sessionContentPanel);
        tty = reconnectTty;
        term.setTtyConnector(reconnectTty);
        term.getTerminal().setCursorVisible(true);
        syncTtySize();
        term.start();
        scheduleReconnectCheck(reconnectTty);
    }

    private boolean isConnectorUsable(DisposableTtyConnector connector) {
        return connector != null && !connector.isCancelled() && connector.hasReceivedData();
    }

    private void showReconnectBanner(String message) {
        reconnectLabel.setText(message);
        if (reconnectionBox.getParent() == null) {
            contentPane.add(reconnectionBox, BorderLayout.NORTH);
        }
        contentPane.revalidate();
        contentPane.repaint();
    }

    private void hideReconnectBanner() {
        contentPane.remove(reconnectionBox);
        contentPane.revalidate();
        contentPane.repaint();
    }

}
