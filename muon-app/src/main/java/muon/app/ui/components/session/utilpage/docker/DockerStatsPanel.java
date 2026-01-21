package muon.app.ui.components.session.utilpage.docker;

import lombok.extern.slf4j.Slf4j;
import muon.app.App;
import muon.app.ui.components.common.SkinnedScrollPane;
import muon.app.ui.components.session.SessionContentPanel;
import muon.app.ui.components.session.utilpage.UtilPageItemView;
import muon.app.util.FormatUtils;
import muon.app.util.OptionPaneUtils;
import muon.app.util.SudoUtils;

import javax.swing.*;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static muon.app.util.ScalingUtil.getScaledEmptyBorder;
import static muon.app.util.ScalingUtil.scale;

@Slf4j
public class DockerStatsPanel extends UtilPageItemView {
    private static final int REFRESH_INTERVAL_MS = 2000;
    private static final String DOCKER_STATS_COMMAND =
            "docker stats --no-stream --format \"{{.Name}}\\t{{.Container}}\\t{{.CPUPerc}}\\t{{.MemUsage}}\\t{{.MemPerc}}\\t{{.NetIO}}\\t{{.BlockIO}}\\t{{.PIDs}}\"";
    private static final Pattern SIZE_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]+)?");

    private static final int COL_NAME = 0;
    private static final int COL_CONTAINER = 1;
    private static final int COL_CPU = 2;
    private static final int COL_MEM_USAGE = 3;
    private static final int COL_MEM_PERCENT = 4;
    private static final int COL_NET_IO = 5;
    private static final int COL_BLOCK_IO = 6;
    private static final int COL_PIDS = 7;

    private final DockerStatsTableModel model = new DockerStatsTableModel();
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
    private JTable table;
    private Timer timer;
    private JLabel statusLabel;
    private JCheckBox chkRunAsSuperUser;
    private String cachedSudoPassword;

    public DockerStatsPanel(SessionContentPanel holder) {
        super(holder);
    }

    @Override
    protected void createUI() {
        table = new JTable(model);
        table.setShowGrid(false);
        table.setIntercellSpacing(scale(new Dimension(0, 0)));
        table.setFillsViewportHeight(true);

        TableRowSorter<DockerStatsTableModel> sorter = new TableRowSorter<>(model);
        sorter.setComparator(COL_CPU, (a, b) -> Double.compare(parsePercent(a), parsePercent(b)));
        sorter.setComparator(COL_MEM_PERCENT, (a, b) -> Double.compare(parsePercent(a), parsePercent(b)));
        sorter.setComparator(COL_MEM_USAGE, (a, b) -> Long.compare(parseFirstBytes(a), parseFirstBytes(b)));
        sorter.setComparator(COL_NET_IO, (a, b) -> Long.compare(parseTotalBytes(a), parseTotalBytes(b)));
        sorter.setComparator(COL_BLOCK_IO, (a, b) -> Long.compare(parseTotalBytes(a), parseTotalBytes(b)));
        sorter.setComparator(COL_PIDS, (a, b) -> Integer.compare(parseInt(a), parseInt(b)));
        table.setRowSorter(sorter);

        JLabel titleLabel = new JLabel(App.getCONTEXT().getBundle().getString("docker_stats"));
        titleLabel.setFont(new Font(Font.DIALOG, Font.PLAIN, 18));
        titleLabel.setBorder(getScaledEmptyBorder(5, 10, 5, 10));

        chkRunAsSuperUser = new JCheckBox(App.getCONTEXT().getBundle().getString("actions_sudo"));
        Box rightBox = Box.createHorizontalBox();
        rightBox.add(chkRunAsSuperUser);
        rightBox.setBorder(getScaledEmptyBorder(5, 10, 5, 10));

        statusLabel = new JLabel(" ");
        statusLabel.setBorder(getScaledEmptyBorder(5, 10, 5, 10));

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(titleLabel, BorderLayout.WEST);
        topPanel.add(rightBox, BorderLayout.EAST);

        add(topPanel, BorderLayout.NORTH);
        add(new SkinnedScrollPane(table));
        add(statusLabel, BorderLayout.SOUTH);

        timer = new Timer(REFRESH_INTERVAL_MS, e -> refreshStats());
        timer.setInitialDelay(0);
        timer.setCoalesce(true);
    }

    @Override
    protected void onComponentVisible() {
        timer.start();
    }

    @Override
    protected void onComponentHide() {
        timer.stop();
    }

    private void refreshStats() {
        if (!refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        holder.EXECUTOR.submit(() -> {
            try {
                if (holder.isSessionClosed()) {
                    SwingUtilities.invokeLater(() -> timer.stop());
                    return;
                }
                AtomicBoolean stopFlag = new AtomicBoolean(false);
                StringBuilder output = new StringBuilder();
                StringBuilder error = new StringBuilder();
                int ret;
                if (chkRunAsSuperUser.isSelected()) {
                    String password = holder.getInfo().getPassword();
                    if (password == null || password.isBlank()) {
                        password = cachedSudoPassword;
                    }
                    if (password == null || password.isBlank()) {
                        password = promptForPassword();
                        if (password == null || password.isBlank()) {
                            SwingUtilities.invokeLater(() -> chkRunAsSuperUser.setSelected(false));
                            return;
                        }
                        cachedSudoPassword = password;
                    }
                    ret = SudoUtils.runSudoWithOutput(DOCKER_STATS_COMMAND, holder.getRemoteSessionInstance(),
                                                      output, error, password);
                } else {
                    ret = holder.getRemoteSessionInstance().exec(DOCKER_STATS_COMMAND, stopFlag, output, error);
                }
                List<DockerStatsEntry> entries = ret == 0 ? parseStats(output.toString()) : new ArrayList<>();
                String status = null;
                if (ret != 0) {
                    if (error.length() > 0) {
                        status = error.toString().trim();
                    } else if (output.length() > 0) {
                        status = output.toString().trim();
                    } else {
                        status = App.getCONTEXT().getBundle().getString("operation_failed");
                    }
                }
                String statusText = status == null ? " " : FormatUtils.limitTextOutput(status, 240);
                SwingUtilities.invokeLater(() -> {
                    model.setEntries(entries);
                    statusLabel.setText(statusText.isEmpty() ? " " : statusText);
                });
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            } finally {
                refreshInProgress.set(false);
            }
        });
    }

    private String promptForPassword() {
        if (SwingUtilities.isEventDispatchThread()) {
            return showPasswordDialog();
        }
        final String[] result = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> result[0] = showPasswordDialog());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return result[0];
    }

    private String showPasswordDialog() {
        JPasswordField passwordField = new JPasswordField(30);
        int ret = OptionPaneUtils.showOptionDialog(this,
                                                   new Object[]{App.getCONTEXT().getBundle().getString("user_password"),
                                                           passwordField},
                                                   App.getCONTEXT().getBundle().getString("authentication"));
        if (ret == JOptionPane.OK_OPTION) {
            return new String(passwordField.getPassword());
        }
        return null;
    }

    private List<DockerStatsEntry> parseStats(String output) {
        List<DockerStatsEntry> entries = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return entries;
        }
        for (String line : output.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length < 8) {
                continue;
            }
            DockerStatsEntry entry = new DockerStatsEntry();
            entry.setName(parts[0].trim());
            entry.setContainerId(parts[1].trim());
            entry.setCpuPercent(parts[2].trim());
            entry.setMemUsage(parts[3].trim());
            entry.setMemPercent(parts[4].trim());
            entry.setNetIo(parts[5].trim());
            entry.setBlockIo(parts[6].trim());
            entry.setPids(parts[7].trim());
            entries.add(entry);
        }
        return entries;
    }

    private static double parsePercent(Object value) {
        if (value == null) {
            return 0.0;
        }
        String text = value.toString().trim();
        if (text.endsWith("%")) {
            text = text.substring(0, text.length() - 1);
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static int parseInt(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseFirstBytes(Object value) {
        if (value == null) {
            return 0L;
        }
        String text = value.toString();
        int sep = text.indexOf('/');
        String first = sep >= 0 ? text.substring(0, sep) : text;
        return parseBytes(first.trim());
    }

    private static long parseTotalBytes(Object value) {
        if (value == null) {
            return 0L;
        }
        String text = value.toString();
        int sep = text.indexOf('/');
        if (sep < 0) {
            return parseBytes(text.trim());
        }
        String first = text.substring(0, sep).trim();
        String second = text.substring(sep + 1).trim();
        return parseBytes(first) + parseBytes(second);
    }

    private static long parseBytes(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) {
            return 0L;
        }
        Matcher matcher = SIZE_PATTERN.matcher(value.trim());
        if (!matcher.find()) {
            return 0L;
        }
        double number;
        try {
            number = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0L;
        }
        String unit = matcher.group(2);
        if (unit == null || unit.isEmpty()) {
            unit = "B";
        }
        unit = unit.toUpperCase(Locale.ENGLISH);
        double multiplier;
        switch (unit) {
            case "B":
                multiplier = 1d;
                break;
            case "KB":
                multiplier = 1_000d;
                break;
            case "MB":
                multiplier = 1_000_000d;
                break;
            case "GB":
                multiplier = 1_000_000_000d;
                break;
            case "TB":
                multiplier = 1_000_000_000_000d;
                break;
            case "KIB":
                multiplier = 1024d;
                break;
            case "MIB":
                multiplier = 1024d * 1024d;
                break;
            case "GIB":
                multiplier = 1024d * 1024d * 1024d;
                break;
            case "TIB":
                multiplier = 1024d * 1024d * 1024d * 1024d;
                break;
            default:
                multiplier = 1d;
                break;
        }
        return (long) (number * multiplier);
    }
}
