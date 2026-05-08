package muon.app.ui.components.session;

import muon.app.App;
import muon.app.ui.components.common.SkinnedScrollPane;
import muon.app.ui.components.common.SkinnedTextArea;
import muon.app.ui.components.common.SkinnedTextField;
import muon.app.vps.ProviderRecord;
import muon.app.vps.VpsProviderRepository;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
    private boolean hasUnsavedChanges;
    private boolean suppressSelectionEvents;
    private boolean pendingChangesSaved;

    public ProvidersDialog(Window owner) {
        super(owner, "Providers", ModalityType.APPLICATION_MODAL);
        createUI();
        reloadProviders();
        if (App.getInfisicalSyncService() != null) {
            App.getInfisicalSyncService().editorOpened();
        }
    }

    private void createUI() {
        setLayout(new BorderLayout(scale(10), scale(10)));
        setSize(scale(860), scale(560));
        setLocationRelativeTo(App.getAppWindow());
        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeDialog();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                if (App.getInfisicalSyncService() != null) {
                    App.getInfisicalSyncService().editorClosed();
                }
            }
        });

        providerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        providerList.addListSelectionListener(this::providerSelected);
        SkinnedScrollPane listScroll = new SkinnedScrollPane(providerList);
        listScroll.setPreferredSize(new Dimension(scale(240), scale(420)));
        add(listScroll, BorderLayout.WEST);

        txtNotes.setRows(5);
        txtNotes.setLineWrap(true);
        txtNotes.setWrapStyleWord(true);
        addDirtyTracking();

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
        btnClose.addActionListener(e -> closeDialog());

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
        if (event.getValueIsAdjusting() || suppressSelectionEvents) {
            return;
        }
        ProviderRecord selected = providerList.getSelectedValue();
        String selectedId = selected == null ? null : selected.getId();
        if (isCurrentProvider(selected)) {
            return;
        }
        if (!confirmPendingChanges()) {
            restoreCurrentSelection();
            return;
        }
        if (pendingChangesSaved) {
            reloadProviders(selectedId);
            return;
        }
        current = selected;
        showProvider(current);
    }

    private void reloadProviders() {
        reloadProviders(null);
    }

    private void reloadProviders(String selectedId) {
        suppressSelectionEvents = true;
        try {
            providerModel.clear();
            for (ProviderRecord provider : repository.listProviders()) {
                providerModel.addElement(provider);
            }
            if (!providerModel.isEmpty()) {
                int index = findProviderIndex(selectedId);
                providerList.setSelectedIndex(index >= 0 ? index : 0);
                providerList.ensureIndexIsVisible(providerList.getSelectedIndex());
            } else {
                providerList.clearSelection();
            }
        } finally {
            suppressSelectionEvents = false;
        }
        if (providerModel.isEmpty()) {
            startNewProvider();
        } else {
            current = providerList.getSelectedValue();
            showProvider(current);
        }
    }

    private void newProvider() {
        if (!confirmPendingChanges()) {
            return;
        }
        if (pendingChangesSaved) {
            reloadProviders(current == null ? null : current.getId());
        }
        startNewProvider();
    }

    private void startNewProvider() {
        current = new ProviderRecord();
        suppressSelectionEvents = true;
        try {
            providerList.clearSelection();
        } finally {
            suppressSelectionEvents = false;
        }
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
            clearDirty();
        }
    }

    private void saveProvider() {
        if (saveCurrentProvider()) {
            reloadProviders(current == null ? null : current.getId());
        }
    }

    private void deleteProvider() {
        if (!confirmPendingChanges()) {
            return;
        }
        if (pendingChangesSaved) {
            reloadProviders(current == null ? null : current.getId());
        }
        if (current == null || current.getId() == null || current.getId().isBlank()) {
            return;
        }
        int result = JOptionPane.showConfirmDialog(this, "Delete selected provider?", "Delete", JOptionPane.YES_NO_OPTION);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            repository.deleteProvider(current.getId());
            if (App.getInfisicalSyncService() != null) {
                App.getInfisicalSyncService().notifyLocalStateChanged();
            }
            reloadProviders();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), App.getCONTEXT().getBundle().getString("error"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void selectProvider(String id) {
        if (id == null || id.isBlank()) {
            providerList.clearSelection();
            return;
        }
        for (int i = 0; i < providerModel.size(); i++) {
            ProviderRecord provider = providerModel.get(i);
            if (id.equals(provider.getId())) {
                providerList.setSelectedIndex(i);
                providerList.ensureIndexIsVisible(i);
                return;
            }
        }
    }

    private int findProviderIndex(String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int i = 0; i < providerModel.size(); i++) {
            ProviderRecord provider = providerModel.get(i);
            if (id.equals(provider.getId())) {
                return i;
            }
        }
        return -1;
    }

    private boolean isCurrentProvider(ProviderRecord selected) {
        if (current == selected) {
            return true;
        }
        if (current == null || selected == null || current.getId() == null || current.getId().isBlank()) {
            return false;
        }
        return current.getId().equals(selected.getId());
    }

    private void restoreCurrentSelection() {
        suppressSelectionEvents = true;
        try {
            if (current != null && current.getId() != null && !current.getId().isBlank()) {
                selectProvider(current.getId());
            } else {
                providerList.clearSelection();
            }
        } finally {
            suppressSelectionEvents = false;
        }
    }

    private void closeDialog() {
        if (confirmPendingChanges()) {
            dispose();
        }
    }

    private boolean confirmPendingChanges() {
        pendingChangesSaved = false;
        if (!hasUnsavedChanges) {
            return true;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                App.getCONTEXT().getBundle().getString("confirm_close_unsaved"),
                "Providers",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
            return false;
        }
        if (choice == JOptionPane.YES_OPTION) {
            if (!saveCurrentProvider()) {
                return false;
            }
            pendingChangesSaved = true;
            return true;
        }
        showProvider(current);
        return true;
    }

    private boolean saveCurrentProvider() {
        if (updatingFields) {
            return true;
        }
        ProviderRecord provider = createProviderFromFields();
        try {
            current = repository.upsertProvider(provider);
            clearDirty();
            if (App.getInfisicalSyncService() != null) {
                App.getInfisicalSyncService().notifyLocalStateChanged();
            }
            return true;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), App.getCONTEXT().getBundle().getString("error"),
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private ProviderRecord createProviderFromFields() {
        ProviderRecord provider = new ProviderRecord();
        if (current != null) {
            provider.setId(current.getId());
            provider.setUpdatedAt(current.getUpdatedAt());
            provider.setDeleted(current.isDeleted());
        }
        provider.setName(txtName.getText());
        provider.setWebsite(txtWebsite.getText());
        provider.setPanelUrl(txtPanelUrl.getText());
        provider.setBillingUrl(txtBillingUrl.getText());
        provider.setAccountId(txtAccountId.getText());
        provider.setTags(txtTags.getText());
        provider.setNotes(txtNotes.getText());
        return provider;
    }

    private void addDirtyTracking() {
        addDirtyTracking(txtName);
        addDirtyTracking(txtWebsite);
        addDirtyTracking(txtPanelUrl);
        addDirtyTracking(txtBillingUrl);
        addDirtyTracking(txtAccountId);
        addDirtyTracking(txtTags);
        addDirtyTracking(txtNotes);
    }

    private void addDirtyTracking(JTextComponent component) {
        component.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                markDirty();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                markDirty();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                markDirty();
            }
        });
    }

    private void markDirty() {
        if (!updatingFields) {
            hasUnsavedChanges = true;
        }
    }

    private void clearDirty() {
        hasUnsavedChanges = false;
    }
}
