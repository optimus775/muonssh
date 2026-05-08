package muon.app.ui.components.session;

import muon.app.App;
import muon.app.ui.components.common.SkinnedScrollPane;
import muon.app.vps.VpsDateFormat;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static muon.app.util.ScalingUtil.getScaledEmptyBorder;
import static muon.app.util.ScalingUtil.scale;

public class VpsOverviewDialog extends JDialog {

    private final List<SessionInfo> allHosts;
    private final DefaultTableModel tableModel;
    private final JTextField hostFilter;
    private final JTextField providerFilter;
    private final JTextField statusFilter;
    private final JTextField tagFilter;

    public VpsOverviewDialog(Window owner, SessionFolder rootFolder) {
        super(owner, "VPS Ledger", ModalityType.APPLICATION_MODAL);
        this.allHosts = collectHosts(rootFolder);
        this.tableModel = new DefaultTableModel(
                new Object[]{"Name", "Host", "Provider", "Billing mode", "Status", "Next due", "Price", "Tags"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        this.hostFilter = new JTextField(12);
        this.providerFilter = new JTextField(12);
        this.statusFilter = new JTextField(10);
        this.tagFilter = new JTextField(12);
        createUI();
        refresh();
    }

    private void createUI() {
        setLayout(new BorderLayout());
        setSize(scale(980), scale(540));
        setLocationRelativeTo(App.getAppWindow());

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, scale(8), scale(8)));
        filters.setBorder(getScaledEmptyBorder(8, 8, 8, 8));
        filters.add(new JLabel("Host"));
        filters.add(hostFilter);
        filters.add(new JLabel("Provider"));
        filters.add(providerFilter);
        filters.add(new JLabel("Status"));
        filters.add(statusFilter);
        filters.add(new JLabel("Tag"));
        filters.add(tagFilter);

        JButton apply = new JButton("Apply");
        JButton clear = new JButton("Clear");
        apply.addActionListener(e -> refresh());
        clear.addActionListener(e -> {
            hostFilter.setText("");
            providerFilter.setText("");
            statusFilter.setText("");
            tagFilter.setText("");
            refresh();
        });
        filters.add(apply);
        filters.add(clear);

        JTable table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        add(filters, BorderLayout.NORTH);
        add(new SkinnedScrollPane(table), BorderLayout.CENTER);

        JButton close = new JButton(App.getCONTEXT().getBundle().getString("cancel"));
        close.addActionListener(e -> dispose());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(close);
        add(bottom, BorderLayout.SOUTH);
    }

    private void refresh() {
        tableModel.setRowCount(0);
        for (SessionInfo info : allHosts) {
            if (!matches(info)) {
                continue;
            }
            tableModel.addRow(new Object[]{
                    info.getName(),
                    info.getHost(),
                    info.getProvider(),
                    info.getBillingPeriodType(),
                    info.getVpsStatus(),
                    VpsDateFormat.toDisplayDate(getDueDate(info)),
                    formatPrice(info),
                    info.getTags()
            });
        }
    }

    private boolean matches(SessionInfo info) {
        return contains(info.getHost(), hostFilter.getText())
                && contains(info.getProvider(), providerFilter.getText())
                && contains(info.getVpsStatus(), statusFilter.getText())
                && contains(info.getTags(), tagFilter.getText());
    }

    private boolean contains(String source, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return Objects.toString(source, "")
                .toLowerCase(Locale.ROOT)
                .contains(filter.toLowerCase(Locale.ROOT).trim());
    }

    private String formatPrice(SessionInfo info) {
        if ("hourly_balance".equals(info.getBillingPeriodType()) && info.getHourlyRate() != null && !info.getHourlyRate().isBlank()) {
            return info.getHourlyRate() + " " + Objects.toString(info.getCurrency(), "") + "/h";
        }
        if (info.getPrice() == null || info.getPrice().isBlank()) {
            return "";
        }
        return info.getPrice() + " " + Objects.toString(info.getCurrency(), "");
    }

    private String getDueDate(SessionInfo info) {
        if ("hourly_balance".equals(info.getBillingPeriodType())) {
            return info.getNextBalanceCheckDate();
        }
        return info.getNextPaymentDate();
    }

    private List<SessionInfo> collectHosts(SessionFolder folder) {
        List<SessionInfo> hosts = new ArrayList<>();
        if (folder == null) {
            return hosts;
        }
        hosts.addAll(folder.getItems());
        for (SessionFolder child : folder.getFolders()) {
            hosts.addAll(collectHosts(child));
        }
        return hosts;
    }
}
