package muon.app.ui.components.session;

import muon.app.App;
import muon.app.ui.components.common.SkinnedScrollPane;
import muon.app.ui.components.common.SkinnedTextArea;
import muon.app.ui.components.common.SkinnedTextField;
import muon.app.vps.ProviderRecord;
import muon.app.vps.VpsProviderRepository;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import java.awt.*;
import java.io.IOException;

import static muon.app.util.ScalingUtil.getScaledEmptyBorder;
import static muon.app.util.ScalingUtil.scale;
import static muon.app.util.ScalingUtil.scaleInsets;

public class ProvidersDialog extends JDialog {

    private final VpsProviderRepository repository = new VpsProviderRepository();
    private final DefaultListModel<ProviderRecord> providerModel = new DefaultListModel<>();
    private final JList<ProviderRecord> providerList = new JList<>(providerModel);
    private final JTextField txtName = new SkinnedTextField(24);
    private final JTextField txtWebsite = new SkinnedTextField(24);
    private final JTextField txtPanelUrl = new SkinnedTextField(24);
    private final JTextField txtBillingUrl = new SkinnedTextField(24);
    private final JTextField txtAccountId = new SkinnedTextField(24);
    private final JTextField txtTags = new SkinnedTextField(24);
    private final SkinnedTextArea txtNotes = new SkinnedTextArea();

    private ProviderRecord current;
    private boolean updatingFields;

    public ProvidersDialog(Window owner) {
        super(owner, "Providers", ModalityType.APPLICATION_MODAL);
        createUI();
        reloadProviders();
    }

    private void createUI() {
        setLayout(new BorderLayout(scale(10), scale(10)));
        setSize(scale(860), scale(560));
        setLocationRelativeTo(App.getAppWindow());

        providerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        providerList.addListSelectionListener(this::providerSelected);
        SkinnedScrollPane listScroll = new SkinnedScrollPane(providerList);
        listScroll.setPreferredSize(new Dimension(scale(240), scale(420)));
        add(listScroll, BorderLayout.WEST);

        txtNotes.setRows(5);
        txtNotes.setLineWrap(true);
        txtNotes.setWrapStyleWord(true);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(getScaledEmptyBorder(10, 10, 0, 10));
        int row = 0;
        row = addRow(form, row, "Name", txtName);
        row = addRow(form, row, "Website", txtWebsite);
        row = addRow(form, row, "Panel URL", txtPanelUrl);
        row = addRow(form, row, "Billing URL", txtBillingUrl);
        row = addRow(form, row, "Account ID", txtAccountId);
        row = addRow(form, row, "Tags", txtTags);
        row = addRow(form, row, "Notes", new SkinnedScrollPane(txtNotes));

        GridBagConstraints spacer = new GridBagConstraints();
        spacer.gridx = 0;
        spacer.gridy = row;
        spacer.gridwidth = 2;
        spacer.weightx = 1;
        spacer.weighty = 1;
        spacer.fill = GridBagConstraints.BOTH;
        form.add(new JPanel(), spacer);
        add(form, BorderLayout.CENTER);

        JButton btnNew = new JButton("New");
        JButton btnSave = new JButton("Save");
        JButton btnDelete = new JButton("Delete");
        JButton btnClose = new JButton(App.getCONTEXT().getBundle().getString("cancel"));
        btnNew.addActionListener(e -> newProvider());
        btnSave.addActionListener(e -> saveProvider());
        btnDelete.addActionListener(e -> deleteProvider());
        btnClose.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, scale(8), scale(8)));
        buttons.add(btnNew);
        buttons.add(btnSave);
        buttons.add(btnDelete);
        buttons.add(btnClose);
        add(buttons, BorderLayout.SOUTH);
    }

    private int addRow(JPanel form, int row, String label, Component field) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.insets = scaleInsets(8, 0, 0, 10);
        labelConstraints.anchor = GridBagConstraints.LINE_START;
        form.add(new JLabel(label), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.insets = scaleInsets(8, 0, 0, 0);
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, fieldConstraints);
        return row + 1;
    }

    private void providerSelected(ListSelectionEvent event) {
        if (event.getValueIsAdjusting()) {
            return;
        }
        current = providerList.getSelectedValue();
        showProvider(current);
    }

    private void reloadProviders() {
        providerModel.clear();
        for (ProviderRecord provider : repository.listProviders()) {
            providerModel.addElement(provider);
        }
        if (!providerModel.isEmpty()) {
            providerList.setSelectedIndex(0);
        } else {
            newProvider();
        }
    }

    private void newProvider() {
        current = new ProviderRecord();
        providerList.clearSelection();
        showProvider(current);
    }

    private void showProvider(ProviderRecord provider) {
        updatingFields = true;
        try {
            txtName.setText(provider == null ? "" : provider.getName());
            txtWebsite.setText(provider == null ? "" : provider.getWebsite());
            txtPanelUrl.setText(provider == null ? "" : provider.getPanelUrl());
            txtBillingUrl.setText(provider == null ? "" : provider.getBillingUrl());
            txtAccountId.setText(provider == null ? "" : provider.getAccountId());
            txtTags.setText(provider == null ? "" : provider.getTags());
            txtNotes.setText(provider == null ? "" : provider.getNotes());
        } finally {
            updatingFields = false;
        }
    }

    private void saveProvider() {
        if (updatingFields) {
            return;
        }
        if (current == null) {
            current = new ProviderRecord();
        }
        current.setName(txtName.getText());
        current.setWebsite(txtWebsite.getText());
        current.setPanelUrl(txtPanelUrl.getText());
        current.setBillingUrl(txtBillingUrl.getText());
        current.setAccountId(txtAccountId.getText());
        current.setTags(txtTags.getText());
        current.setNotes(txtNotes.getText());
        try {
            ProviderRecord saved = repository.upsertProvider(current);
            reloadProviders();
            selectProvider(saved.getId());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), App.getCONTEXT().getBundle().getString("error"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteProvider() {
        if (current == null || current.getId() == null || current.getId().isBlank()) {
            return;
        }
        int result = JOptionPane.showConfirmDialog(this, "Delete selected provider?", "Delete", JOptionPane.YES_NO_OPTION);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            repository.deleteProvider(current.getId());
            reloadProviders();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), App.getCONTEXT().getBundle().getString("error"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void selectProvider(String id) {
        for (int i = 0; i < providerModel.size(); i++) {
            ProviderRecord provider = providerModel.get(i);
            if (id.equals(provider.getId())) {
                providerList.setSelectedIndex(i);
                providerList.ensureIndexIsVisible(i);
                return;
            }
        }
    }
}
