package muon.app.ui.components.session;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import muon.app.App;
import muon.app.ui.AppWindow;
import muon.app.ui.components.common.SkinnedScrollPane;
import muon.app.ui.components.session.dialog.NewSessionDlg;
import muon.app.util.FontAwesomeContants;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static muon.app.util.Constants.SMALL_TEXT_SIZE;
import static muon.app.util.ScalingUtil.getScaledEmptyBorder;
import static muon.app.util.ScalingUtil.scale;

/**
 * @author subhro
 */
@Slf4j
public class SessionListPanel extends JPanel {
    private static final Cursor HAND_CURSOR = new Cursor(Cursor.HAND_CURSOR);
    private static final Cursor DEFAULT_CURSOR = new Cursor(Cursor.DEFAULT_CURSOR);
    private static final Cursor E_RESIZE_CURSOR = new Cursor(Cursor.E_RESIZE_CURSOR);

    private final DefaultListModel<ISessionContentPanel> sessionListModel;
    @Getter
    private final JList<ISessionContentPanel> sessionList;
    private final AppWindow window;
    private ISessionContentPanel activeSession;
    private ISessionContentPanel activeSessionOnMousePress;

    // Resizing config
    private final int minWidthPx = scale(170);
    private final int maxWidthPx = scale(560);
    private final int gripWidthPx = scale(8);

    public SessionListPanel(AppWindow window) {
        super(new BorderLayout());
        this.window = window;
        sessionListModel = new DefaultListModel<>();
        sessionList = new JList<>(sessionListModel) {
            @Override
            protected void processMouseEvent(MouseEvent e) {
                if (e.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(e)) {
                    activeSessionOnMousePress = activeSession;
                }
                super.processMouseEvent(e);
            }
        };
        sessionList.setCursor(DEFAULT_CURSOR);
        sessionList.setCellRenderer(new SessionListRenderer());

        JScrollPane scrollPane = new SkinnedScrollPane(sessionList);

        // Container that allows us to place a resize grip on the right edge
        JPanel content = new JPanel(new BorderLayout());
        content.add(scrollPane, BorderLayout.CENTER);

        EdgeGrip edgeGrip = new EdgeGrip();
        content.add(edgeGrip, BorderLayout.EAST);

        this.add(content, BorderLayout.CENTER);

        // Initial preferred width
        int initialWidthPx = scale(170);
        int init = clamp(initialWidthPx, minWidthPx, maxWidthPx);
        setPreferredSize(new Dimension(init, getPreferredSize().height));
        setMinimumSize(new Dimension(minWidthPx, 0));
        setMaximumSize(new Dimension(maxWidthPx, Integer.MAX_VALUE));

        setMouseListener();
        setMouseMotionListener();
        setAddListSelectionListener();
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private void revalidateAll() {
        this.revalidate();
        this.repaint();
        Container p = getParent();
        if (p != null) {
            p.revalidate();
            p.repaint();
        }
        if (window != null) {
            window.revalidate();
            window.repaint();
        }
    }

    private void setAddListSelectionListener() {
        sessionList.addListSelectionListener(e -> {
            log.debug("called for index: {} {} {}{}", sessionList.getSelectedIndex(), e.getFirstIndex(), e.getLastIndex(), e.getValueIsAdjusting());
            if (!e.getValueIsAdjusting()) {
                int index = sessionList.getSelectedIndex();
                if (index != -1) {
                    this.selectSession(index);
                }
            }
        });
    }

    private void setMouseMotionListener() {
        sessionList.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int index = getCellIndex(e.getPoint());
                if (index != -1) {
                    if (isCloseClick(index, e.getPoint()) || sessionListModel.get(index) instanceof SessionContentPanel) {
                        sessionList.setCursor(HAND_CURSOR);
                        return;
                    }
                }
                sessionList.setCursor(DEFAULT_CURSOR);
            }
        });
    }

    private void setMouseListener() {
        sessionList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int index = getCellIndex(e.getPoint());
                if (index == -1) {
                    return;
                }
                if (isCloseClick(index, e.getPoint())) {
                    log.info("Clicked on disconnect for session: {}", index);
                    removeSession(index);
                    return;
                }
                ISessionContentPanel clickedSession = sessionListModel.get(index);
                if (clickedSession == activeSessionOnMousePress) {
                    openSessionManager(index);
                } else {
                    if (sessionList.getSelectedIndex() != index) {
                        sessionList.setSelectedIndex(index);
                    }
                    selectSession(index);
                }
                activeSessionOnMousePress = null;
            }

            @Override
            public void mouseExited(MouseEvent e) {
                sessionList.setCursor(DEFAULT_CURSOR);
            }
        });
    }

    private int getCellIndex(Point point) {
        int index = sessionList.locationToIndex(point);
        if (index == -1) {
            return -1;
        }
        Rectangle bounds = sessionList.getCellBounds(index, index);
        return bounds != null && bounds.contains(point) ? index : -1;
    }

    private boolean isCloseClick(int index, Point point) {
        Rectangle r = sessionList.getCellBounds(index, index);
        if (r == null || !r.contains(point)) {
            return false;
        }
        int rightPad = scale(30);
        int vPad = scale(10);
        int x = point.x;
        int y = point.y;
        return x > r.x + r.width - rightPad && x < r.x + r.width
                && y > r.y + vPad && y < r.y + r.height - vPad;
    }

    private void openSessionManager(int index) {
        ISessionContentPanel sessionContentPanel = sessionListModel.get(index);
        if (!(sessionContentPanel instanceof SessionContentPanel) || sessionContentPanel.getInfo() == null) {
            return;
        }
        int activeSessionId = sessionContentPanel.getActiveSessionId();
        SessionInfo info = new NewSessionDlg(window, sessionContentPanel.getInfo(),
                () -> removeSessionByActiveSessionId(activeSessionId, false)).newSession();
        if (info != null) {
            createSession(info);
        }
    }

    public void createSession(SessionInfo info) {
        SessionContentPanel panel = new SessionContentPanel(info);
        sessionListModel.insertElementAt(panel, 0);
        sessionList.setSelectedIndex(0);
    }

    public void createLocalSession() {
        LocalSessionContentPanel panel = new LocalSessionContentPanel(null);
        sessionListModel.insertElementAt(panel, 0);
        sessionList.setSelectedIndex(0);
    }

    public void selectSession(int index) {
        if (index < 0 || index >= sessionListModel.size()) {
            return;
        }
        ISessionContentPanel sessionContentPanel = sessionListModel.get(index);
        if (sessionContentPanel == activeSession) {
            return;
        }
        activeSession = sessionContentPanel;
        window.showSession(sessionContentPanel);
        window.revalidate();
        window.repaint();
    }

    public boolean removeSession(int index) {
        return removeSession(index, true);
    }

    public void closeAllSessionsForShutdown() {
        List<ISessionContentPanel> sessions = new ArrayList<>();
        for (int i = 0; i < sessionListModel.size(); i++) {
            sessions.add(sessionListModel.get(i));
        }
        for (ISessionContentPanel session : sessions) {
            try {
                session.closeForShutdown();
            } catch (Exception e) {
                log.error("Failed to close session during shutdown", e);
            }
        }
    }

    private boolean removeSession(int index, boolean confirm) {
        if (!confirm || !App.getGlobalSettings().isConfirmBeforeTerminalClosing() ||
                JOptionPane.showConfirmDialog(window, App.getCONTEXT().getBundle().getString("disconnect_session"))
                        == JOptionPane.YES_OPTION) {
            ISessionContentPanel sessionContentPanel = sessionListModel.get(index);
            sessionContentPanel.close();
            window.removeSession(sessionContentPanel);
            window.revalidate();
            window.repaint();
            if (sessionContentPanel == activeSession) {
                activeSession = null;
            }
            if (sessionContentPanel == activeSessionOnMousePress) {
                activeSessionOnMousePress = null;
            }
            sessionListModel.remove(index);
            if (sessionListModel.isEmpty()) {
                activeSession = null;
                activeSessionOnMousePress = null;
                return true;
            }
            int nextIndex = index == sessionListModel.size() ? index - 1 : index;
            if (sessionList.getSelectedIndex() != nextIndex) {
                sessionList.setSelectedIndex(nextIndex);
            }
            selectSession(nextIndex);
            return true;
        }
        return false;
    }

    private boolean removeSessionByActiveSessionId(int activeSessionId, boolean confirm) {
        for (int i = 0; i < sessionListModel.size(); i++) {
            if (sessionListModel.get(i).getActiveSessionId() == activeSessionId) {
                return removeSession(i, confirm);
            }
        }
        return false;
    }

    public ISessionContentPanel getSessionContainer(int activeSessionId) {
        for (int i = 0; i < sessionListModel.size(); i++) {
            ISessionContentPanel scp = sessionListModel.get(i);
            if (scp.getActiveSessionId() == activeSessionId) {
                return scp;
            }
        }
        return null;
    }

    public static final class SessionListRenderer implements ListCellRenderer<ISessionContentPanel> {

        private final JPanel panel;
        private final JLabel lblIcon;
        private final JLabel lblText;
        private final JLabel lblHost;
        private final JLabel lblClose;

        public SessionListRenderer() {
            lblIcon = new JLabel();
            lblText = new JLabel();
            lblHost = new JLabel();
            lblClose = new JLabel();

            lblIcon.setFont(App.getCONTEXT().getSkin().getIconFont(scale(24.0f)));
            lblText.setFont(App.getCONTEXT().getSkin().getDefaultFont(scale(SMALL_TEXT_SIZE)));
            lblHost.setFont(App.getCONTEXT().getSkin().getDefaultFont(scale(12.0f)));
            lblClose.setFont(App.getCONTEXT().getSkin().getIconFont(scale(SMALL_TEXT_SIZE)));

            lblText.setText("Sample server");
            lblHost.setText("server host");
            lblIcon.setText(FontAwesomeContants.FA_CUBE);
            lblClose.setText(FontAwesomeContants.FA_EJECT);

            JPanel textHolder = new JPanel(new BorderLayout(5, 0));
            textHolder.setOpaque(false);
            textHolder.add(lblText);
            textHolder.add(lblHost, BorderLayout.SOUTH);

            panel = new JPanel(new BorderLayout(5, 5));
            panel.add(lblIcon, BorderLayout.WEST);
            panel.add(lblClose, BorderLayout.EAST);
            panel.add(textHolder);

            panel.setBorder(getScaledEmptyBorder(10, 10, 10, 10));
            panel.setBackground(App.getCONTEXT().getSkin().getDefaultBackground());
            panel.setOpaque(true);

            Dimension d = panel.getPreferredSize();
            panel.setPreferredSize(d);
            panel.setMaximumSize(d);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ISessionContentPanel> list,
                                                      ISessionContentPanel value,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {

            SessionInfo info = value.getInfo();

            if (value instanceof LocalSessionContentPanel) {
                info = new SessionInfo();
                info.setHost("Localhost");
                info.setName("Local Terminal");
            }

            lblText.setText(info.getName());
            lblHost.setText(info.getHost());
            lblIcon.setText(FontAwesomeContants.FA_CUBE);
            lblClose.setText(FontAwesomeContants.FA_EJECT);

            lblText.setName("lblText");
            lblHost.setName("lblHost");
            lblIcon.setName("lblIcon");
            lblClose.setName("lblClose");

            boolean isPanelVisible = list.isVisible();
            lblText.setVisible(isPanelVisible);
            lblHost.setVisible(isPanelVisible);

            panel.setBackground(App.getCONTEXT().getSkin().getDefaultBackground());
            lblText.setForeground(App.getCONTEXT().getSkin().getDefaultForeground());
            lblHost.setForeground(App.getCONTEXT().getSkin().getInfoTextForeground());
            lblIcon.setForeground(App.getCONTEXT().getSkin().getDefaultForeground());

            if (isSelected) {
                panel.setBackground(App.getCONTEXT().getSkin().getDefaultSelectionBackground());
                lblText.setForeground(App.getCONTEXT().getSkin().getDefaultSelectionForeground());
                lblHost.setForeground(App.getCONTEXT().getSkin().getDefaultSelectionForeground());
                lblIcon.setForeground(App.getCONTEXT().getSkin().getDefaultSelectionForeground());
            }

            return panel;
        }
    }

    /**
     * A thin panel docked at the EAST that captures drags and updates this panel's preferred width.
     * Parent revalidation is handled by outer class.
     */
    private final class EdgeGrip extends JPanel {
        private boolean dragging = false;
        private int dragStartXOnScreen;
        private int startWidthPx;

        EdgeGrip() {
            setOpaque(false);
            setCursor(E_RESIZE_CURSOR);
            setPreferredSize(new Dimension(gripWidthPx, 1));
            setMinimumSize(new Dimension(gripWidthPx, 1));
            setMaximumSize(new Dimension(gripWidthPx, Integer.MAX_VALUE));

            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.getButton() != MouseEvent.BUTTON1) return;
                    dragging = true;
                    dragStartXOnScreen = e.getXOnScreen();
                    startWidthPx = SessionListPanel.this.getWidth();
                    e.consume();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (!dragging) return;
                    int delta = e.getXOnScreen() - dragStartXOnScreen;
                    int newWidth = clamp(startWidthPx + delta, minWidthPx, maxWidthPx);

                    Dimension pref = SessionListPanel.this.getPreferredSize();
                    if (pref.width != newWidth) {
                        SessionListPanel.this.setPreferredSize(new Dimension(newWidth, pref.height));
                        SessionListPanel.this.setMinimumSize(new Dimension(minWidthPx, 0));
                        SessionListPanel.this.setMaximumSize(new Dimension(maxWidthPx, Integer.MAX_VALUE));
                        revalidateAll();
                    }
                    e.consume();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (dragging) {
                        dragging = false;
                        e.consume();
                    }
                }
            };

            addMouseListener(ma);
            addMouseMotionListener(ma);
        }
    }
}
