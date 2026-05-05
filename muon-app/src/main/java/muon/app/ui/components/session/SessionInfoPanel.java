package muon.app.ui.components.session;

import lombok.extern.slf4j.Slf4j;
import muon.app.App;
import muon.app.ui.components.common.SkinnedScrollPane;
import muon.app.ui.components.common.SkinnedTextArea;
import muon.app.ui.components.common.SkinnedTextField;
import muon.app.ui.components.common.TabbedPanel;
import muon.app.util.enums.JumpType;
import muon.app.vps.ProviderRecord;
import muon.app.vps.VpsDateFormat;
import muon.app.vps.VpsProviderRepository;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;

import static muon.app.util.ScalingUtil.*;


@Slf4j
public class SessionInfoPanel extends JPanel {

    public static final int DEFAULT_MAX_PORT = 65535;
    private static final long serialVersionUID = 6679029920589652547L;
    private static final String ORIG_COMBO_RENDERER = "orig.combo.renderer";
    private static final String BILLING_MODE_CONTROL = "billing.mode.control";
    private static final String BILLING_MODE_LABEL = "billing.mode.label";
    private static final String ORIGINAL_FOREGROUND = "billing.mode.original.foreground";
    private JTextField inpHostName;
    private JTextField inpUserName;
    private JPasswordField inpPassword;
    private JTextField inpLocalFolder;
    private JTextField inpRemoteFolder;
    private JTextField inpKeyFile;
    private JLabel lblLocalFolder;
    private JLabel lblRemoteFolder;
    private SpinnerNumberModel portModel;
    private SpinnerNumberModel proxyPortModel;
    private JComboBox<String> cmbProxy;
    private JTextField inpProxyHostName;
    private JTextField inpProxyUserName;
    private JPasswordField inpProxyPassword;
    private JCheckBox chkUseJumpHosts;
    private JRadioButton radMultiHopTunnel;
    private JRadioButton radMultiHopPortForwarding;
    private JumpHostPanel panJumpHost;
    private PortForwardingPanel panPF;
    private SessionInfo info;
    private JCheckBox chkUseX11Forwarding;
    private JCheckBox chkSftpOnly;
    private final VpsProviderRepository providerRepository = new VpsProviderRepository();
    private DefaultComboBoxModel<ProviderRecord> providerModel;
    private JComboBox<ProviderRecord> cmbProvider;
    private JTextField inpAccountId;
    private JRadioButton radBillingPeriod;
    private JRadioButton radBillingDays;
    private JRadioButton radBillingHourly;
    private JComboBox<String> cmbBillingCycle;
    private SpinnerNumberModel billingCycleDaysModel;
    private JPanel billingPeriodPanel;
    private JPanel billingDaysPanel;
    private JPanel billingHourlyPanel;
    private JPanel fixedPaymentPanel;
    private JTextField inpPrice;
    private JTextField inpCurrency;
    private JTextField inpNextPaymentDate;
    private JTextField inpHourlyRate;
    private JTextField inpNextBalanceCheckDate;
    private JTextField inpCancelByDate;
    private JCheckBox chkAutoPay;
    private JComboBox<String> cmbVpsStatus;
    private JTextField inpTags;
    private SkinnedTextArea inpDescription;
    private SkinnedTextArea inpExternalRefs;
    private JCheckBox chkSyncPrivateKey;
    private JCheckBox chkSyncPublicKey;
    private Runnable changeListener;
    private boolean suppressChangeEvents;
    private boolean editable = true;

    private JPanel proxyPanel;
    private JPanel jumpPanel;
    private JPanel portForwardingPanel;

    public SessionInfoPanel() {
        createUI();
    }

    public void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener;
    }

    private void notifyChange() {
        if (suppressChangeEvents) {
            return;
        }
        if (info != null) {
            info.setUpdatedAt(System.currentTimeMillis());
        }
        if (changeListener != null) {
            changeListener.run();
        }
    }

    private static void setEnableSubComponents(Component c, boolean enabled) {
        c.setEnabled(enabled);
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                setEnableSubComponents(child, enabled);
            }
        }
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
        applyEditability(this, editable);
        if (panJumpHost != null) {
            panJumpHost.setEditable(editable);
        }
        if (panPF != null) {
            panPF.setEditable(editable);
        }
        if (info != null) {
            applySftpOnlyState(info.isSftpOnly(), false);
        }
        applyReadOnlyColors(editable);
        updateBillingModeState();
    }

    private void applyEditability(Component component, boolean editable) {
        if (component instanceof JLabel) {
            component.setEnabled(true);
            return;
        }

        if (component instanceof JTextComponent) {
            JTextComponent textComponent = (JTextComponent) component;
            textComponent.setEnabled(true);
            textComponent.setEditable(editable);
            textComponent.setFocusable(editable);
            return;
        }

        if (component instanceof JComboBox || component instanceof JSpinner || component instanceof AbstractButton
                || component instanceof JTable || component instanceof JList || component instanceof JTree) {
            component.setEnabled(editable);
        }

        if (component instanceof Container) {
            Container container = (Container) component;
            for (Component child : container.getComponents()) {
                applyEditability(child, editable);
            }
        }
    }

    private void applyReadOnlyColors(boolean editable) {
        Color readOnlyBg = App.getCONTEXT().getSkin().getReadOnlyFieldBackground();
        Color readOnlyFg = App.getCONTEXT().getSkin().getReadOnlyFieldForeground();

        for (Component c : getAllComponents(this)) {
            boolean formControl = c instanceof JTextComponent || c instanceof JComboBox || c instanceof JSpinner || c instanceof JCheckBox || c instanceof JButton;
            if (!formControl) continue;
            if (editable && isBillingModeComponent(c)) continue;

            boolean readOnlyText = c instanceof JTextComponent && !((JTextComponent) c).isEditable();
            boolean disabled = !c.isEnabled();
            if (readOnlyText || disabled) {
                c.setBackground(readOnlyBg);
                c.setForeground(readOnlyFg);
                if (c instanceof JComponent) {
                    ((JComponent) c).setOpaque(true);
                }
                if (c instanceof JTextComponent) {
                    ((JTextComponent) c).setDisabledTextColor(readOnlyFg);
                    ((JTextComponent) c).setCaretColor(readOnlyFg);
                }
                if (c instanceof JSpinner) {
                    JComponent editor = ((JSpinner) c).getEditor();
                    if (editor instanceof JSpinner.DefaultEditor) {
                        JTextField tf = ((JSpinner.DefaultEditor) editor).getTextField();
                        tf.setBackground(readOnlyBg);
                        tf.setForeground(readOnlyFg);
                        tf.setDisabledTextColor(readOnlyFg);
                    }
                    for (Component sc : ((JSpinner) c).getComponents()) {
                        if (sc instanceof JButton || sc instanceof JComponent) {
                            sc.setBackground(readOnlyBg);
                            sc.setForeground(readOnlyFg);
                            if (sc instanceof JComponent) {
                                ((JComponent) sc).setOpaque(true);
                            }
                        }
                    }
                }
                if (c instanceof JComboBox) {
                    JComboBox<?> combo = (JComboBox<?>) c;
                    combo.setBackground(readOnlyBg);
                    combo.setForeground(readOnlyFg);
                    applyComboRenderer(combo, readOnlyBg, readOnlyFg, true);
                    ComboBoxEditor editor = combo.getEditor();
                    if (editor != null && editor.getEditorComponent() instanceof JComponent) {
                        JComponent ec = (JComponent) editor.getEditorComponent();
                        ec.setBackground(readOnlyBg);
                        ec.setForeground(readOnlyFg);
                        ec.setOpaque(true);
                    }
                }
            } else {
                c.setBackground(null);
                c.setForeground(null);
                if (c instanceof JTextComponent) {
                    ((JTextComponent) c).setDisabledTextColor(UIManager.getColor("nimbusDisabledText"));
                }
                if (c instanceof JComboBox) {
                    applyComboRenderer((JComboBox<?>) c, readOnlyBg, readOnlyFg, false);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyComboRenderer(JComboBox<?> combo, Color bg, Color fg, boolean readOnly) {
        if (readOnly) {
            if (combo.getClientProperty(ORIG_COMBO_RENDERER) == null) {
                combo.putClientProperty(ORIG_COMBO_RENDERER, combo.getRenderer());
            }
            combo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                              boolean isSelected, boolean cellHasFocus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (index == -1 || isSelected) {
                        label.setBackground(bg);
                        label.setForeground(fg);
                    }
                    return label;
                }
            });
        } else {
            Object original = combo.getClientProperty(ORIG_COMBO_RENDERER);
            if (original instanceof ListCellRenderer) {
                combo.setRenderer((ListCellRenderer<? super Object>) original);
            }
        }
    }

    private java.util.List<Component> getAllComponents(Container c) {
        java.util.List<Component> list = new java.util.ArrayList<>();
        for (Component comp : c.getComponents()) {
            list.add(comp);
            if (comp instanceof Container) {
                list.addAll(getAllComponents((Container) comp));
            }
        }
        return list;
    }

    public boolean validateFields() {
        if (inpHostName.getText().isEmpty()) {
            showError("Host name can not be left blank");
            return false;
        }
        if (inpUserName.getText().isEmpty()) {
            showError("User name can not be left blank");
            return false;
        }
        boolean hourlyBilling = "hourly".equals(selectedBillingMode());
        if (!hourlyBilling && !normalizeDateField(inpNextPaymentDate, value -> info.setNextPaymentDate(value))) {
            showError("Next payment date must be empty or use DD-MM-YYYY / DD-MM");
            return false;
        }
        if (!hourlyBilling && !normalizeDateField(inpCancelByDate, value -> info.setCancelByDate(value))) {
            showError("Cancel-by date must be empty or use DD-MM-YYYY / DD-MM");
            return false;
        }
        if (hourlyBilling && !normalizeDateField(inpNextBalanceCheckDate, value -> info.setNextBalanceCheckDate(value))) {
            showError("Next balance check date must be empty or use DD-MM-YYYY / DD-MM");
            return false;
        }
        return true;
    }

    public void setSessionInfo(SessionInfo info) {
        this.info = info;
        suppressChangeEvents = true;
        try {
            setHost(info.getHost());
            setPort(info.getPort());
            setLocalFolder(info.getLocalFolder());
            setRemoteFolder(info.getRemoteFolder());
            setUser(info.getUser());
            setPassword(info.getPassword() == null ? new char[0] : info.getPassword().toCharArray());
            setKeyFile(info.getPrivateKeyFile());
            setProxyType(info.getProxyType());
            setProxyHost(info.getProxyHost());
            setProxyPort(info.getProxyPort());
            setProxyUser(info.getProxyUser());

            setProxyPassword(info.getProxyPassword() == null ? new char[0] : info.getProxyPassword().toCharArray());

            setJumpHostDetails(info.isUseJumpHosts(), info.getJumpType(), info.getJumpHosts());
            this.chkUseX11Forwarding.setSelected(info.isUseX11Forwarding());
            this.chkSftpOnly.setSelected(info.isSftpOnly());
            setVpsFields(info);

            panPF.setInfo(info);
        } finally {
            suppressChangeEvents = false;
        }
    }

    private void setHost(String host) {
        inpHostName.setText(host);
    }

    private void setPort(int port) {
        portModel.setValue(port);
    }

    private void setUser(String user) {
        inpUserName.setText(user);
    }

    private void setPassword(char[] pass) {
        inpPassword.setText(new String(pass));
    }

    private void setProxyType(int type) {
        cmbProxy.setSelectedIndex(type);
    }

    private void setProxyHost(String host) {
        inpProxyHostName.setText(host);
    }

    private void setProxyPort(int port) {
        proxyPortModel.setValue(port);
    }

    private void setProxyUser(String user) {
        inpProxyUserName.setText(user);
    }

    private void setProxyPassword(char[] pass) {
        inpProxyPassword.setText(new String(pass));
    }

    private void setLocalFolder(String folder) {
        inpLocalFolder.setText(folder);
    }

    private void setRemoteFolder(String folder) {
        inpRemoteFolder.setText(folder);
    }

    private void setKeyFile(String keyFile) {
        inpKeyFile.setText(keyFile);
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, App.getCONTEXT().getBundle().getString("error"), JOptionPane.ERROR_MESSAGE);
    }

    private void setJumpHostDetails(boolean useJumpHosts, JumpType jumpType, List<HopEntry> jumpHosts) {
        this.chkUseJumpHosts.setSelected(useJumpHosts);
        if (jumpType == JumpType.TCP_FORWARDING) {
            radMultiHopTunnel.setSelected(true);
        } else {
            radMultiHopPortForwarding.setSelected(true);
        }
        panJumpHost.setInfo(info);
    }

    private void createUI() {
        setLayout(new BorderLayout());
        setBorder(getScaledEmptyBorder(10, 0, 10, 0));
        TabbedPanel tabs = new TabbedPanel();
        tabs.addTab(App.getCONTEXT().getBundle().getString("connection"), createConnectionPanel());
        tabs.addTab("Description", createDescriptionPanel());
        tabs.addTab(App.getCONTEXT().getBundle().getString("directories"), createDirectoryPanel());
        proxyPanel = createProxyPanel();
        tabs.addTab(App.getCONTEXT().getBundle().getString("proxy"), proxyPanel);
        jumpPanel = createJumpPanel();
        tabs.addTab(App.getCONTEXT().getBundle().getString("jump_hosts"), jumpPanel);
        portForwardingPanel = createPortForwardingPanel();
        tabs.addTab(App.getCONTEXT().getBundle().getString("port_forwarding"), portForwardingPanel);
        this.add(tabs);
        tabs.setSelectedIndex(0);
    }

    public void reloadProviders() {
        if (providerModel == null) {
            return;
        }
        String selectedId = info == null ? null : info.getProviderId();
        ProviderRecord selected = getSelectedProvider();
        if ((selectedId == null || selectedId.isBlank()) && selected != null) {
            selectedId = selected.getId();
        }
        providerModel.removeAllElements();
        ProviderRecord empty = new ProviderRecord();
        providerModel.addElement(empty);
        for (ProviderRecord provider : providerRepository.listProviders()) {
            providerModel.addElement(provider);
        }
        selectProvider(selectedId, info == null ? null : info.getProvider());
    }

    private Component createDescriptionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        Insets labelInset = scaleInsets(14, 10, 0, 10);
        Insets fieldInset = scaleInsets(5, 10, 0, 10);

        providerModel = new DefaultComboBoxModel<>();
        cmbProvider = new JComboBox<>(providerModel);
        reloadProviders();
        cmbProvider.addActionListener(e -> {
            if (info != null) {
                ProviderRecord provider = getSelectedProvider();
                info.setProviderId(provider == null ? null : provider.getId());
                info.setProvider(provider == null ? null : provider.getName());
                info.setProviderUrl(provider == null ? null : firstNonBlank(provider.getBillingUrl(), provider.getWebsite()));
                touchAndNotify();
            }
        });
        inpAccountId = new SkinnedTextField(10);
        bindText(inpAccountId, value -> info.setAccountId(value));

        radBillingPeriod = new JRadioButton("Fixed period");
        radBillingDays = new JRadioButton("Fixed number of days");
        radBillingHourly = new JRadioButton("Hourly balance");
        ButtonGroup billingModeGroup = new ButtonGroup();
        billingModeGroup.add(radBillingPeriod);
        billingModeGroup.add(radBillingDays);
        billingModeGroup.add(radBillingHourly);
        radBillingPeriod.addActionListener(e -> selectBillingMode("period"));
        radBillingDays.addActionListener(e -> selectBillingMode("days"));
        radBillingHourly.addActionListener(e -> selectBillingMode("hourly"));

        cmbBillingCycle = new JComboBox<>(new String[]{"monthly", "yearly"});
        cmbBillingCycle.addActionListener(e -> {
            if (info != null && radBillingPeriod.isSelected()) {
                info.setBillingCycle((String) cmbBillingCycle.getSelectedItem());
                applyPeriodDaysFromCycle();
                touchAndNotify();
            }
        });
        billingCycleDaysModel = new SpinnerNumberModel(30, 1, 3650, 1);
        billingCycleDaysModel.addChangeListener(e -> {
            if (info != null && radBillingDays.isSelected()) {
                info.setBillingPeriodType("fixed_period");
                info.setBillingCycle("custom");
                info.setBillingCycleDays((Integer) billingCycleDaysModel.getValue());
                info.setBillingPeriodDays((Integer) billingCycleDaysModel.getValue());
                touchAndNotify();
            }
        });

        inpPrice = new SkinnedTextField(10);
        bindText(inpPrice, value -> info.setPrice(value));
        inpCurrency = new SkinnedTextField(10);
        bindText(inpCurrency, value -> info.setCurrency(value));
        inpNextPaymentDate = new SkinnedTextField(10);
        inpNextPaymentDate.setToolTipText(VpsDateFormat.DISPLAY_PATTERN + " or DD-MM");
        bindDateText(inpNextPaymentDate, value -> info.setNextPaymentDate(value));
        inpHourlyRate = new SkinnedTextField(10);
        bindText(inpHourlyRate, value -> info.setHourlyRate(value));
        inpNextBalanceCheckDate = new SkinnedTextField(10);
        inpNextBalanceCheckDate.setToolTipText(VpsDateFormat.DISPLAY_PATTERN + " or DD-MM");
        bindDateText(inpNextBalanceCheckDate, value -> info.setNextBalanceCheckDate(value));
        inpCancelByDate = new SkinnedTextField(10);
        inpCancelByDate.setToolTipText(VpsDateFormat.DISPLAY_PATTERN + " or DD-MM");
        bindDateText(inpCancelByDate, value -> info.setCancelByDate(value));

        chkAutoPay = new JCheckBox("Auto-pay enabled");
        chkAutoPay.addActionListener(e -> {
            if (info != null) {
                info.setAutoPay(chkAutoPay.isSelected());
                touchAndNotify();
            }
        });
        cmbVpsStatus = new JComboBox<>(new String[]{"active", "testing", "trial", "paused", "cancelled", "deleted", "unknown"});
        cmbVpsStatus.addActionListener(e -> {
            if (info != null) {
                info.setVpsStatus((String) cmbVpsStatus.getSelectedItem());
                touchAndNotify();
            }
        });
        inpTags = new SkinnedTextField(10);
        bindText(inpTags, value -> info.setTags(value));
        inpDescription = new SkinnedTextArea();
        inpDescription.setRows(4);
        inpDescription.setLineWrap(true);
        inpDescription.setWrapStyleWord(true);
        bindText(inpDescription, value -> info.setDescription(value));
        inpExternalRefs = new SkinnedTextArea();
        inpExternalRefs.setRows(3);
        inpExternalRefs.setLineWrap(true);
        inpExternalRefs.setWrapStyleWord(true);
        bindText(inpExternalRefs, value -> info.setExternalRefs(value));

        chkSyncPrivateKey = new JCheckBox("Sync private key to Infisical");
        chkSyncPrivateKey.addActionListener(e -> {
            if (info != null) {
                info.setSyncPrivateKey(chkSyncPrivateKey.isSelected());
                touchAndNotify();
            }
        });
        chkSyncPublicKey = new JCheckBox("Sync public key to Infisical");
        chkSyncPublicKey.addActionListener(e -> {
            if (info != null) {
                info.setSyncPublicKey(chkSyncPublicKey.isSelected());
                touchAndNotify();
            }
        });

        int row = 0;
        addLabel(panel, "Provider", row++, labelInset);
        addField(panel, cmbProvider, row++, fieldInset);
        addLabel(panel, "Account / order ID", row++, labelInset);
        addField(panel, inpAccountId, row++, fieldInset);
        addField(panel, createBillingSection(), row++, labelInset);
        addLabel(panel, "Status", row++, labelInset);
        addField(panel, cmbVpsStatus, row++, fieldInset);
        addLabel(panel, "Tags", row++, labelInset);
        addField(panel, inpTags, row++, fieldInset);
        addLabel(panel, "Description / notes", row++, labelInset);
        addField(panel, new JScrollPane(inpDescription), row++, fieldInset);
        addLabel(panel, "External refs", row++, labelInset);
        addField(panel, new JScrollPane(inpExternalRefs), row++, fieldInset);
        addField(panel, chkSyncPrivateKey, row++, fieldInset);
        addField(panel, chkSyncPublicKey, row++, fieldInset);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 1;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        panel.add(new JPanel(), c);
        SkinnedScrollPane scrollPane = new SkinnedScrollPane(panel);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(scale(18));
        scrollPane.getVerticalScrollBar().setBlockIncrement(scale(90));
        return scrollPane;
    }

    private Component createBillingSection() {
        JPanel section = new JPanel(new GridBagLayout());
        section.setBorder(BorderFactory.createTitledBorder("Billing cycle"));

        JPanel modePanel = new JPanel(new GridLayout(0, 1, 0, scale(4)));
        modePanel.add(radBillingPeriod);
        modePanel.add(radBillingDays);
        modePanel.add(radBillingHourly);

        billingPeriodPanel = createBillingGroupPanel("Period");
        addCompactRow(billingPeriodPanel, 0, "Period", cmbBillingCycle);

        billingDaysPanel = createBillingGroupPanel("Number of days");
        addCompactRow(billingDaysPanel, 0, "Days", new JSpinner(billingCycleDaysModel));

        billingHourlyPanel = createBillingGroupPanel("Hourly");
        addCompactRow(billingHourlyPanel, 0, "Hourly rate", inpHourlyRate);
        addCompactRow(billingHourlyPanel, 1, "Next balance check", inpNextBalanceCheckDate);

        fixedPaymentPanel = createBillingGroupPanel("Fixed payment");
        addCompactRow(fixedPaymentPanel, 0, "Price", inpPrice);
        addCompactRow(fixedPaymentPanel, 1, "Next payment date", inpNextPaymentDate);
        addCompactRow(fixedPaymentPanel, 2, "Cancel by date", inpCancelByDate);
        addCompactRow(fixedPaymentPanel, 3, "", chkAutoPay);

        JPanel currencyPanel = new JPanel(new GridBagLayout());
        addCompactRow(currencyPanel, 0, "Currency", inpCurrency);

        int row = 0;
        addSectionRow(section, modePanel, row++, scaleInsets(8, 8, 0, 8));
        addSectionRow(section, billingPeriodPanel, row++, scaleInsets(8, 8, 0, 8));
        addSectionRow(section, billingDaysPanel, row++, scaleInsets(8, 8, 0, 8));
        addSectionRow(section, billingHourlyPanel, row++, scaleInsets(8, 8, 0, 8));
        addSectionRow(section, currencyPanel, row++, scaleInsets(8, 8, 0, 8));
        addSectionRow(section, fixedPaymentPanel, row, scaleInsets(8, 8, 8, 8));
        return section;
    }

    private JPanel createBillingGroupPanel(String title) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        return panel;
    }

    private void addCompactRow(JPanel panel, int row, String label, Component component) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.insets = scaleInsets(3, 8, 3, 8);
        labelConstraints.anchor = GridBagConstraints.LINE_START;
        if (!label.isEmpty()) {
            JLabel labelComponent = new JLabel(label);
            markBillingModeLabel(labelComponent);
            panel.add(labelComponent, labelConstraints);
        }

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1;
        fieldConstraints.insets = scaleInsets(3, label.isEmpty() ? 8 : 0, 3, 8);
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.anchor = GridBagConstraints.LINE_START;
        panel.add(component, fieldConstraints);
        markBillingModeControl(component);
    }

    private void addSectionRow(JPanel panel, Component component, int row, Insets insets) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 1;
        c.insets = insets;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.LINE_START;
        panel.add(component, c);
    }

    private void markBillingModeControl(Component component) {
        if (component instanceof JComponent) {
            ((JComponent) component).putClientProperty(BILLING_MODE_CONTROL, Boolean.TRUE);
        }
        if (component instanceof JSpinner) {
            JComponent editor = ((JSpinner) component).getEditor();
            if (editor instanceof JSpinner.DefaultEditor) {
                ((JSpinner.DefaultEditor) editor).getTextField()
                        .putClientProperty(BILLING_MODE_CONTROL, Boolean.TRUE);
            }
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                markBillingModeControl(child);
            }
        }
    }

    private void markBillingModeLabel(JLabel label) {
        label.putClientProperty(BILLING_MODE_LABEL, Boolean.TRUE);
        label.putClientProperty(ORIGINAL_FOREGROUND, label.getForeground());
    }

    private boolean isBillingModeComponent(Component component) {
        return component instanceof JComponent
                && Boolean.TRUE.equals(((JComponent) component).getClientProperty(BILLING_MODE_CONTROL));
    }

    private ProviderRecord getSelectedProvider() {
        Object selected = cmbProvider == null ? null : cmbProvider.getSelectedItem();
        if (selected instanceof ProviderRecord && !((ProviderRecord) selected).isBlank()) {
            return (ProviderRecord) selected;
        }
        return null;
    }

    private void selectProvider(String providerId, String providerName) {
        if (providerModel == null) {
            return;
        }
        for (int i = 0; i < providerModel.getSize(); i++) {
            ProviderRecord provider = providerModel.getElementAt(i);
            if (providerId != null && providerId.equals(provider.getId())) {
                cmbProvider.setSelectedIndex(i);
                return;
            }
            if ((providerId == null || providerId.isBlank()) && providerName != null
                    && providerName.equalsIgnoreCase(provider.getName())) {
                cmbProvider.setSelectedIndex(i);
                return;
            }
        }
        cmbProvider.setSelectedIndex(0);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private void addLabel(JPanel panel, String text, int row, Insets insets) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 1;
        c.insets = insets;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.LINE_START;
        panel.add(new JLabel(text), c);
    }

    private void addField(JPanel panel, Component component, int row, Insets insets) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 1;
        c.insets = insets;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.LINE_START;
        panel.add(component, c);
    }

    private void bindText(JTextComponent component, Consumer<String> setter) {
        component.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                update();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                update();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                update();
            }

            private void update() {
                if (info == null) {
                    return;
                }
                setter.accept(component.getText());
                touchAndNotify();
            }
        });
    }

    private void bindDateText(JTextComponent component, Consumer<String> setter) {
        bindText(component, value -> setter.accept(toDateStorageOrRaw(value)));
        component.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                normalizeDateField(component, setter);
            }
        });
    }

    private String toDateStorageOrRaw(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (!VpsDateFormat.isValidOptional(value)) {
            return value;
        }
        return VpsDateFormat.toStorageDate(value);
    }

    private boolean normalizeDateField(JTextComponent component, Consumer<String> setter) {
        String value = component.getText();
        if (value == null || value.isBlank()) {
            setter.accept("");
            return true;
        }
        if (!VpsDateFormat.isValidOptional(value)) {
            return false;
        }
        String storageDate = VpsDateFormat.toStorageDate(value);
        String displayDate = VpsDateFormat.toDisplayDate(storageDate);
        setter.accept(storageDate);
        if (!displayDate.equals(value)) {
            component.setText(displayDate);
        }
        return true;
    }

    private void touchAndNotify() {
        notifyChange();
    }

    private void selectBillingMode(String mode) {
        if (info == null) {
            updateBillingModeState();
            return;
        }
        applyBillingModeToInfo(mode);
        updateBillingModeState();
        applyReadOnlyColors(editable);
        updateBillingModeState();
        touchAndNotify();
    }

    private void applyBillingModeToInfo(String mode) {
        if ("hourly".equals(mode)) {
            info.setBillingPeriodType("hourly_balance");
            info.setBillingCycle("hourly");
            return;
        }

        info.setBillingPeriodType("fixed_period");
        if ("days".equals(mode)) {
            info.setBillingCycle("custom");
            int days = (Integer) billingCycleDaysModel.getValue();
            info.setBillingCycleDays(days);
            info.setBillingPeriodDays(days);
            return;
        }

        String cycle = (String) cmbBillingCycle.getSelectedItem();
        if (cycle == null || cycle.isBlank()) {
            cycle = "monthly";
            cmbBillingCycle.setSelectedItem(cycle);
        }
        info.setBillingCycle(cycle);
        applyPeriodDaysFromCycle();
    }

    private void applyPeriodDaysFromCycle() {
        if (info == null) {
            return;
        }
        String cycle = (String) cmbBillingCycle.getSelectedItem();
        int days = "yearly".equals(cycle) ? 365 : 30;
        info.setBillingCycleDays(days);
        info.setBillingPeriodDays(days);
    }

    private String resolveBillingMode(SessionInfo info) {
        if (info == null) {
            return "period";
        }
        if ("hourly_balance".equals(info.getBillingPeriodType())) {
            return "hourly";
        }
        if ("custom".equals(info.getBillingCycle())) {
            return "days";
        }
        return "period";
    }

    private String selectedBillingMode() {
        if (radBillingHourly != null && radBillingHourly.isSelected()) {
            return "hourly";
        }
        if (radBillingDays != null && radBillingDays.isSelected()) {
            return "days";
        }
        return "period";
    }

    private void updateBillingModeState() {
        if (radBillingPeriod == null) {
            return;
        }
        boolean canEdit = editable;
        boolean period = radBillingPeriod.isSelected();
        boolean days = radBillingDays.isSelected();
        boolean hourly = radBillingHourly.isSelected();

        radBillingPeriod.setEnabled(canEdit);
        radBillingDays.setEnabled(canEdit);
        radBillingHourly.setEnabled(canEdit);
        setBillingGroupState(billingPeriodPanel, period, canEdit);
        setBillingGroupState(billingDaysPanel, days, canEdit);
        setBillingGroupState(billingHourlyPanel, hourly, canEdit);
        setBillingGroupState(fixedPaymentPanel, !hourly, canEdit);
        inpCurrency.setEnabled(canEdit);
        inpCurrency.setEditable(canEdit);
        inpCurrency.setFocusable(canEdit);
    }

    private void setBillingGroupState(Component component, boolean active, boolean canEdit) {
        if (component == null) {
            return;
        }
        boolean controlEnabled = active && canEdit;
        component.setEnabled(canEdit);
        if (component instanceof JPanel) {
            updateBillingPanelTitle((JPanel) component, active);
        }
        if (component instanceof JLabel && Boolean.TRUE.equals(((JLabel) component).getClientProperty(BILLING_MODE_LABEL))) {
            applyBillingLabelColor((JLabel) component, active);
            component.setEnabled(true);
            return;
        }
        if (component instanceof JTextComponent) {
            JTextComponent textComponent = (JTextComponent) component;
            textComponent.setEnabled(true);
            textComponent.setEditable(controlEnabled);
            textComponent.setFocusable(controlEnabled);
            textComponent.setBackground(null);
            textComponent.setForeground(null);
            textComponent.setDisabledTextColor(UIManager.getColor("TextField.foreground"));
            textComponent.setCaretColor(UIManager.getColor("TextField.foreground"));
            return;
        }
        if (component instanceof JComboBox || component instanceof AbstractButton) {
            component.setEnabled(controlEnabled);
            if (component instanceof AbstractButton) {
                ((AbstractButton) component).setForeground(active ? null : getMutedBillingColor());
            }
        }
        if (component instanceof JSpinner) {
            JSpinner spinner = (JSpinner) component;
            spinner.setEnabled(controlEnabled);
            JComponent editor = spinner.getEditor();
            if (editor instanceof JSpinner.DefaultEditor) {
                JTextField textField = ((JSpinner.DefaultEditor) editor).getTextField();
                textField.setEnabled(true);
                textField.setEditable(controlEnabled);
                textField.setFocusable(controlEnabled);
                textField.setBackground(null);
                textField.setForeground(null);
            }
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                setBillingGroupState(child, active, canEdit);
            }
        }
    }

    private void updateBillingPanelTitle(JPanel panel, boolean active) {
        if (panel.getBorder() instanceof TitledBorder) {
            ((TitledBorder) panel.getBorder()).setTitleColor(active ? getDefaultBillingColor(panel) : getMutedBillingColor());
        }
    }

    private void applyBillingLabelColor(JLabel label, boolean active) {
        label.setForeground(active ? getDefaultBillingColor(label) : getMutedBillingColor());
    }

    private Color getDefaultBillingColor(JComponent component) {
        Object original = component.getClientProperty(ORIGINAL_FOREGROUND);
        if (original instanceof Color) {
            return (Color) original;
        }
        Color color = UIManager.getColor("Label.foreground");
        return color == null ? component.getForeground() : color;
    }

    private Color getMutedBillingColor() {
        Color color = App.getCONTEXT().getSkin().getReadOnlyFieldForeground();
        return color == null ? UIManager.getColor("Label.disabledForeground") : color;
    }

    private void setVpsFields(SessionInfo info) {
        reloadProviders();
        selectProvider(info.getProviderId(), info.getProvider());
        inpAccountId.setText(info.getAccountId());
        String billingMode = resolveBillingMode(info);
        radBillingPeriod.setSelected("period".equals(billingMode));
        radBillingDays.setSelected("days".equals(billingMode));
        radBillingHourly.setSelected("hourly".equals(billingMode));
        String cycle = "yearly".equals(info.getBillingCycle()) ? "yearly" : "monthly";
        cmbBillingCycle.setSelectedItem(cycle);
        int periodDays = info.getBillingPeriodDays() > 0 ? info.getBillingPeriodDays() : info.getBillingCycleDays();
        billingCycleDaysModel.setValue(periodDays <= 0 ? 30 : periodDays);
        inpPrice.setText(info.getPrice());
        inpCurrency.setText(info.getCurrency() == null ? "USD" : info.getCurrency());
        inpNextPaymentDate.setText(VpsDateFormat.toDisplayDate(info.getNextPaymentDate()));
        inpHourlyRate.setText(info.getHourlyRate());
        inpNextBalanceCheckDate.setText(VpsDateFormat.toDisplayDate(info.getNextBalanceCheckDate()));
        inpCancelByDate.setText(VpsDateFormat.toDisplayDate(info.getCancelByDate()));
        chkAutoPay.setSelected(info.isAutoPay());
        cmbVpsStatus.setSelectedItem(info.getVpsStatus() == null ? "active" : info.getVpsStatus());
        inpTags.setText(info.getTags());
        inpDescription.setText(info.getDescription());
        inpExternalRefs.setText(info.getExternalRefs());
        chkSyncPrivateKey.setSelected(info.isSyncPrivateKey());
        chkSyncPublicKey.setSelected(info.isSyncPublicKey());
        applyBillingModeToInfo(billingMode);
        updateBillingModeState();
    }

    private JPanel createJumpPanel() {
        GridBagLayout gbl1 = new GridBagLayout();
        JPanel panel = new JPanel(gbl1);

        Insets topInset = scaleInsets(20, 10, 0, 10);
        Insets noInset = scaleInsets(5, 10, 0, 10);

        chkUseJumpHosts = new JCheckBox("Jump Hosts / Multi hop port forwarding");
        radMultiHopTunnel = new JRadioButton("Use multihop SSH tunnel");
        radMultiHopPortForwarding = new JRadioButton("Use multihop port forwarding");

        chkUseJumpHosts.addActionListener(e -> {
            info.setUseJumpHosts(chkUseJumpHosts.isSelected());
            notifyChange();
        });

        radMultiHopPortForwarding.addActionListener(e -> updateHopMode());
        radMultiHopTunnel.addActionListener(e -> updateHopMode());

        ButtonGroup bg = new ButtonGroup();
        bg.add(radMultiHopPortForwarding);
        bg.add(radMultiHopTunnel);

        panJumpHost = new JumpHostPanel();
        panJumpHost.setChangeListener(this::notifyChange);

        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.LINE_START;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.insets = topInset;

        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 1;
        c.weightx = 5;
        c.insets = topInset;
        panel.add(chkUseJumpHosts, c);

        c.gridx = 0;
        c.weightx = 1;
        c.gridwidth = 2;
        c.gridy = 2;
        c.insets = topInset;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(radMultiHopPortForwarding, c);

        c.gridx = 0;
        c.weightx = 1;
        c.gridwidth = 2;
        c.gridy = 3;
        c.insets = topInset;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(radMultiHopTunnel, c);

        c.gridx = 0;
        c.weightx = 1;
        c.gridwidth = 2;
        c.gridy = 4;
        c.insets = topInset;
        c.weighty = 10;
        c.fill = GridBagConstraints.BOTH;
        panel.add(panJumpHost, c);

        return panel;
    }

    private JPanel createPortForwardingPanel() {
        GridBagLayout gbl1 = new GridBagLayout();
        JPanel panel = new JPanel(gbl1);

        panPF = new PortForwardingPanel();
        panPF.setChangeListener(this::notifyChange);

        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.LINE_START;
        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 1;
        c.weightx = 1;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        panel.add(panPF, c);

        return panel;
    }

    private JPanel createProxyPanel() {
        GridBagLayout gbl1 = new GridBagLayout();
        JPanel panel = new JPanel(gbl1);

        Insets topInset = scaleInsets(20, 10, 0, 10);
        Insets noInset = scaleInsets(5, 10, 0, 10);

        // -----------
        JLabel lblProxyType = new JLabel(App.getCONTEXT().getBundle().getString("proxy_type"));
        JLabel lblProxyHost = new JLabel(App.getCONTEXT().getBundle().getString("proxy_host"));
        lblProxyHost.setHorizontalAlignment(JLabel.LEADING);
        JLabel lblProxyPort = new JLabel(App.getCONTEXT().getBundle().getString("proxy_port"));
        JLabel lblProxyUser = new JLabel(App.getCONTEXT().getBundle().getString("proxy_user"));
        JLabel lblProxyPass = new JLabel(App.getCONTEXT().getBundle().getString("proxy_password") + App.getCONTEXT().getBundle().getString("warning_plain_text"));

        cmbProxy = new JComboBox<>(new String[]{"NONE", "HTTP", "SOCKS"});
        cmbProxy.addActionListener(e -> {
            info.setProxyType(cmbProxy.getSelectedIndex());
            notifyChange();
        });

        inpProxyHostName = new SkinnedTextField(10);// new
        inpProxyHostName.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void removeUpdate(DocumentEvent arg0) {
                updateHost();
            }

            @Override
            public void insertUpdate(DocumentEvent arg0) {
                updateHost();
            }

            @Override
            public void changedUpdate(DocumentEvent arg0) {
                updateHost();
            }

            private void updateHost() {
                info.setProxyHost(inpProxyHostName.getText());
                notifyChange();
            }
        });
        proxyPortModel = new SpinnerNumberModel(8080, 1, DEFAULT_MAX_PORT, 1);
        proxyPortModel.addChangeListener(arg0 -> {
            info.setProxyPort((Integer) proxyPortModel.getValue());
            notifyChange();
        });
        JSpinner inpProxyPort = new JSpinner(proxyPortModel);
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(inpProxyPort, "#");
        inpProxyPort.setEditor(editor);
        inpProxyUserName = new SkinnedTextField(10);// new
        inpProxyUserName.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void removeUpdate(DocumentEvent arg0) {
                updateUser();
            }

            @Override
            public void insertUpdate(DocumentEvent arg0) {
                updateUser();
            }

            @Override
            public void changedUpdate(DocumentEvent arg0) {
                updateUser();
            }

            private void updateUser() {
                info.setProxyUser(inpProxyUserName.getText());
                notifyChange();
            }
        });

        inpProxyPassword = new JPasswordField(10);
        inpProxyPassword.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void removeUpdate(DocumentEvent arg0) {
                updatePassword();
            }

            @Override
            public void insertUpdate(DocumentEvent arg0) {
                updatePassword();
            }

            @Override
            public void changedUpdate(DocumentEvent arg0) {
                updatePassword();
            }

            private void updatePassword() {
                info.setProxyPassword(new String(inpProxyPassword.getPassword()));
                notifyChange();
            }
        });
        // -----------

        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.LINE_START;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.insets = topInset;

        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 1;
        c.weightx = 1;
        c.insets = topInset;
        panel.add(lblProxyType, c);

        c.gridx = 0;
        c.weightx = 1;
        c.gridwidth = 1;
        c.gridy = 2;
        c.insets = noInset;
        c.fill = GridBagConstraints.NONE;
        panel.add(cmbProxy, c);

        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 1;
        c.weightx = 1;
        c.insets = topInset;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(lblProxyHost, c);

        c.gridx = 0;
        c.weightx = 1;
        c.gridwidth = 2;
        c.gridy = 4;
        c.insets = noInset;
        panel.add(inpProxyHostName, c);

        c.gridx = 0;
        c.gridy = 5;
        c.gridwidth = 1;
        c.weightx = 1;
        c.insets = topInset;
        panel.add(lblProxyPort, c);

        c.gridx = 0;
        c.weightx = 1;
        c.gridwidth = 2;
        c.gridy = 6;
        c.insets = noInset;
        c.fill = GridBagConstraints.NONE;
        panel.add(inpProxyPort, c);

        c.gridx = 0;
        c.gridy = 7;
        c.gridwidth = 1;
        c.weightx = 1;
        c.insets = topInset;
        panel.add(lblProxyUser, c);

        c.gridx = 0;
        c.weightx = 1;
        c.gridwidth = 2;
        c.gridy = 8;
        c.insets = noInset;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(inpProxyUserName, c);

        c.gridx = 0;
        c.gridy = 9;
        c.gridwidth = 1;
        c.weightx = 5;
        c.insets = topInset;
        panel.add(lblProxyPass, c);

        c.gridx = 0;
        c.weightx = 1;
        c.gridwidth = 2;
        c.gridy = 10;
        c.insets = noInset;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(inpProxyPassword, c);

        JPanel panel2 = new JPanel(new BorderLayout());
        c.gridx = 0;
        c.gridy = 11;
        c.gridwidth = 1;
        c.weightx = 1;
        c.weighty = 10;
        c.fill = GridBagConstraints.BOTH;
        panel.add(panel2, c);

        return panel;
    }

    private JPanel createDirectoryPanel() {
        GridBagLayout gbl1 = new GridBagLayout();
        JPanel panel = new JPanel(gbl1);

        Insets topInset = scaleInsets(20, 10, 0, 10);
        Insets noInset = scaleInsets(5, 10, 0, 10);

        inpLocalFolder = new SkinnedTextField(10);// new
        inpLocalFolder.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void removeUpdate(DocumentEvent arg0) {
                updateFolder();
            }

            @Override
            public void insertUpdate(DocumentEvent arg0) {
                updateFolder();
            }

            @Override
            public void changedUpdate(DocumentEvent arg0) {
                updateFolder();
            }

            private void updateFolder() {
                info.setLocalFolder(inpLocalFolder.getText());
                notifyChange();
            }
        });

        inpRemoteFolder = new SkinnedTextField(10);// new
        inpRemoteFolder.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void removeUpdate(DocumentEvent arg0) {
                updateFolder();
            }

            @Override
            public void insertUpdate(DocumentEvent arg0) {
                updateFolder();
            }

            @Override
            public void changedUpdate(DocumentEvent arg0) {
                updateFolder();
            }

            private void updateFolder() {
                info.setRemoteFolder(inpRemoteFolder.getText());
                notifyChange();
            }
        });

        JButton inpLocalBrowse = new JButton(App.getCONTEXT().getBundle().getString("browse"));
        inpLocalBrowse.addActionListener(e -> {
            JFileChooser jfc = new JFileChooser();
            jfc.setFileHidingEnabled(false);
            jfc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (jfc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                inpLocalFolder.setText(jfc.getSelectedFile().getAbsolutePath());
            }
        });

        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.LINE_START;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.insets = topInset;

        c.gridx = 0;
        c.gridy = 12;
        c.gridwidth = 2;
        c.insets = topInset;
        panel.add(lblLocalFolder, c);

        c.gridx = 0;
        c.gridy = 13;
        c.gridwidth = 1;
        c.insets = noInset;
        c.weightx = 1;
        panel.add(inpLocalFolder, c);

        c.gridx = 1;
        c.gridy = 13;
        c.gridwidth = 1;
        c.insets = scaleInsets(5, 0, 0, 10);
        c.weightx = 0;
        panel.add(inpLocalBrowse, c);

        c.gridx = 0;
        c.gridy = 15;
        c.gridwidth = 2;
        c.insets = topInset;
        panel.add(lblRemoteFolder, c);

        c.gridx = 0;
        c.gridy = 16;
        c.gridwidth = 2;
        c.insets = noInset;
        c.weightx = 1;
        panel.add(inpRemoteFolder, c);

        JPanel panel2 = new JPanel(new BorderLayout());
        c.gridx = 0;
        c.gridy = 20;
        c.gridwidth = 1;
        c.weightx = 1;
        c.weighty = 10;
        c.fill = GridBagConstraints.BOTH;
        panel.add(panel2, c);

        return panel;
    }

    private JPanel createConnectionPanel() {
        GridBagLayout gbl1 = new GridBagLayout();
        JPanel panel = new JPanel(gbl1);

        Insets topInset = scaleInsets(20, 10, 0, 10);
        Insets noInset = scaleInsets(5, 10, 0, 10);

        JLabel lblHost = new JLabel(App.getCONTEXT().getBundle().getString("host"));
        lblHost.setHorizontalAlignment(JLabel.LEADING);
        JLabel lblPort = new JLabel(App.getCONTEXT().getBundle().getString("port"));
        JLabel lblUser = new JLabel(App.getCONTEXT().getBundle().getString("user"));
        JLabel lblPass = new JLabel(App.getCONTEXT().getBundle().getString("password"));
        lblLocalFolder = new JLabel(App.getCONTEXT().getBundle().getString("local_folder"));
        lblRemoteFolder = new JLabel(App.getCONTEXT().getBundle().getString("remote_folder"));
        JLabel lblKeyFile = new JLabel(App.getCONTEXT().getBundle().getString("private_key_file"));

        inpHostName = new SkinnedTextField(10);
        inpHostName.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void removeUpdate(DocumentEvent arg0) {
                updateHost();
            }

            @Override
            public void insertUpdate(DocumentEvent arg0) {
                updateHost();
            }

            @Override
            public void changedUpdate(DocumentEvent arg0) {
                updateHost();
            }

            private void updateHost() {
                info.setHost(inpHostName.getText());
                notifyChange();
            }
        });

        portModel = new SpinnerNumberModel(22, 1, DEFAULT_MAX_PORT, 1);
        portModel.addChangeListener(arg0 -> {
            info.setPort((Integer) portModel.getValue());
            notifyChange();
        });
        JSpinner inpPort = new JSpinner(portModel);
        JSpinner.NumberEditor editorTarget = new JSpinner.NumberEditor(inpPort, "#");
        inpPort.setEditor(editorTarget);
        inpUserName = new SkinnedTextField(10);
        inpUserName.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void removeUpdate(DocumentEvent arg0) {
                updateUser();
            }

            @Override
            public void insertUpdate(DocumentEvent arg0) {
                updateUser();
            }

            @Override
            public void changedUpdate(DocumentEvent arg0) {
                updateUser();
            }

            private void updateUser() {
                info.setUser(inpUserName.getText());
                notifyChange();
            }
        });

        inpPassword = new JPasswordField(10);
        inpPassword.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void removeUpdate(DocumentEvent arg0) {
                updatePassword();
            }

            @Override
            public void insertUpdate(DocumentEvent arg0) {
                updatePassword();
            }

            @Override
            public void changedUpdate(DocumentEvent arg0) {
                updatePassword();
            }

            private void updatePassword() {
                info.setPassword(new String(inpPassword.getPassword()));
                notifyChange();
            }
        });

        inpKeyFile = new SkinnedTextField(10);
        inpKeyFile.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void removeUpdate(DocumentEvent arg0) {
                updateKeyFile();
            }

            @Override
            public void insertUpdate(DocumentEvent arg0) {
                updateKeyFile();
            }

            @Override
            public void changedUpdate(DocumentEvent arg0) {
                updateKeyFile();
            }

            private void updateKeyFile() {
                info.setPrivateKeyFile(inpKeyFile.getText());
                notifyChange();
            }
        });

        JButton inpKeyBrowse = new JButton(App.getCONTEXT().getBundle().getString("browse"));// new
        inpKeyBrowse.addActionListener(e -> {
            JFileChooser jfc = new JFileChooser();
            jfc.setFileHidingEnabled(false);

            jfc.addChoosableFileFilter(new FileNameExtensionFilter("Putty key files (*.ppk)", "ppk"));

            jfc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            if (jfc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                String selectedFile = jfc.getSelectedFile().getAbsolutePath();
                if (selectedFile.endsWith(".ppk") && !isSupportedPuttyKeyFile(jfc.getSelectedFile())) {
                    JOptionPane.showMessageDialog(this, App.getCONTEXT().getBundle().getString("unsupported_key")
                    );
                    return;
                }

                inpKeyFile.setText(jfc.getSelectedFile().getAbsolutePath());
            }
        });

        JButton inpKeyShowPass = new JButton(App.getCONTEXT().getBundle().getString("show"));
        inpKeyShowPass.addActionListener(e -> {
            SkinnedTextArea ta = new SkinnedTextArea();
            ta.setText(inpPassword.getText());
            ta.setEditable(false);
            ta.setLineWrap(false);
            JOptionPane.showMessageDialog(this, ta, App.getCONTEXT().getBundle().getString("password"), JOptionPane.PLAIN_MESSAGE);
        });

        chkUseX11Forwarding = new JCheckBox("X11 forwarding");

        chkUseX11Forwarding.addActionListener(e -> {
            info.setUseX11Forwarding(chkUseX11Forwarding.isSelected());
            notifyChange();
        });

        chkSftpOnly = new JCheckBox("SFTP Only");

        chkSftpOnly.addItemListener(e -> applySftpOnlyState(chkSftpOnly.isSelected()));

        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.LINE_START;
        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 1;
        c.insets = topInset;
        panel.add(lblHost, c);

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.insets = noInset;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(inpHostName, c);

        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        c.insets = topInset;
        panel.add(lblPort, c);

        c.gridx = 0;
        c.gridy = 4;
        c.ipady = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        c.insets = noInset;
        panel.add(inpPort, c);

        c.gridx = 0;
        c.gridy = 5;
        c.insets = topInset;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridwidth = 2;
        panel.add(lblUser, c);

        c.gridx = 0;
        c.gridy = 6;
        c.gridwidth = 2;
        c.insets = noInset;
        panel.add(inpUserName, c);

        c.gridx = 0;
        c.gridy = 7;
        c.gridwidth = 2;
        c.insets = topInset;
        panel.add(lblPass, c);

        c.gridx = 0;
        c.gridy = 8;
        c.gridwidth = 1;
        c.insets = noInset;
        c.weightx = 1;
        panel.add(inpPassword, c);

        c.gridx = 1;
        c.gridy = 8;
        c.gridwidth = 1;
        c.weightx = 0;
        c.insets = scaleInsets(5, 0, 0, 8);
        panel.add(inpKeyShowPass, c);

        c.gridx = 0;
        c.gridy = 9;
        c.gridwidth = 2;
        c.insets = topInset;
        panel.add(lblKeyFile, c);

        c.gridx = 0;
        c.gridy = 10;
        c.gridwidth = 1;
        c.insets = noInset;
        c.weightx = 2;
        panel.add(inpKeyFile, c);

        c.gridx = 1;
        c.gridy = 10;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = scaleInsets(5, 0, 0, 10);
        panel.add(inpKeyBrowse, c);

        c.gridx = 0;
        c.gridy = 11;
        c.gridwidth = 2;
        c.insets = noInset;
        panel.add(chkSftpOnly, c);

        c.gridx = 0;
        c.gridy = 12;
        c.gridwidth = 2;
        c.insets = noInset;
        panel.add(chkUseX11Forwarding, c);

        JPanel panel2 = new JPanel(new BorderLayout());
        c.gridx = 0;
        c.gridy = 12;
        c.gridwidth = 1;
        c.weightx = 1;
        c.weighty = 10;
        c.fill = GridBagConstraints.BOTH;
        panel.add(panel2, c);

        return panel;

    }

    private void updateHopMode() {
        if (radMultiHopPortForwarding.isSelected()) {
            info.setJumpType(JumpType.PORT_FORWARDING);
        } else {
            info.setJumpType(JumpType.TCP_FORWARDING);
        }
        notifyChange();
    }

    private boolean isSupportedPuttyKeyFile(File file) {
        try {
            String content = Files.readString(file.toPath());
            if (content.contains("ssh-ed25519")) {
                return false;
            }
            if (content.contains("Encryption:")
                    && (content.contains("Encryption: aes256-cbc") || content.contains("Encryption: none"))) {
                return true;
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return false;
    }

    private void applySftpOnlyState(boolean sftpOnly) {
        applySftpOnlyState(sftpOnly, true);
    }

    private void applySftpOnlyState(boolean sftpOnly, boolean notify) {
        if (info == null) {
            return;
        }
        info.setSftpOnly(sftpOnly);

        boolean enabledForMode = editable && !sftpOnly;

        if (sftpOnly) {
            chkUseX11Forwarding.setSelected(false);
            info.setUseX11Forwarding(false);
        }
        chkUseX11Forwarding.setEnabled(enabledForMode);
        chkUseX11Forwarding.setForeground(UIManager.getColor(
                enabledForMode ? "Label.foreground" : "Label.disabledForeground"));

        chkUseJumpHosts.setForeground(UIManager.getColor(
                enabledForMode ? "Label.foreground" : "Label.disabledForeground"));


        setEnableSubComponents(proxyPanel, enabledForMode);
        setEnableSubComponents(jumpPanel, enabledForMode);
        setEnableSubComponents(portForwardingPanel, enabledForMode);
        if (panJumpHost != null) {
            panJumpHost.setEditable(enabledForMode);
        }
        if (panPF != null) {
            panPF.setEditable(enabledForMode);
        }

        if (sftpOnly) {
            if (cmbProxy != null) {
                cmbProxy.setSelectedIndex(0);
                info.setProxyType(0);
            }
            if (chkUseJumpHosts != null) {
                chkUseJumpHosts.setSelected(false);
                info.setUseJumpHosts(false);
            }
            if (radMultiHopTunnel != null) radMultiHopTunnel.setSelected(false);
            if (radMultiHopPortForwarding != null) radMultiHopPortForwarding.setSelected(false);
        }

        // Refresh visuals
        proxyPanel.revalidate();
        proxyPanel.repaint();
        jumpPanel.revalidate();
        jumpPanel.repaint();
        portForwardingPanel.revalidate();
        portForwardingPanel.repaint();
        chkUseX11Forwarding.revalidate();
        chkUseX11Forwarding.repaint();
        if (notify) {
            notifyChange();
        }
    }


}
