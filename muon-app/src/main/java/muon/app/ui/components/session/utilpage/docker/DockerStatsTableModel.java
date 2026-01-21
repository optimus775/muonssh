package muon.app.ui.components.session.utilpage.docker;

import muon.app.App;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

public class DockerStatsTableModel extends AbstractTableModel {
    private final String[] columns = {
            App.getCONTEXT().getBundle().getString("name"),
            App.getCONTEXT().getBundle().getString("container"),
            "CPU %",
            "Mem Usage",
            "Mem %",
            "Net I/O",
            "Block I/O",
            "PIDs"
    };
    private final List<DockerStatsEntry> entries = new ArrayList<>();

    @Override
    public int getRowCount() {
        return entries.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int column) {
        return columns[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        DockerStatsEntry entry = entries.get(rowIndex);
        switch (columnIndex) {
            case 0:
                return entry.getName();
            case 1:
                return entry.getContainerId();
            case 2:
                return entry.getCpuPercent();
            case 3:
                return entry.getMemUsage();
            case 4:
                return entry.getMemPercent();
            case 5:
                return entry.getNetIo();
            case 6:
                return entry.getBlockIo();
            case 7:
                return entry.getPids();
            default:
                return "";
        }
    }

    public void setEntries(List<DockerStatsEntry> entries) {
        this.entries.clear();
        if (entries != null) {
            this.entries.addAll(entries);
        }
        fireTableDataChanged();
    }
}
