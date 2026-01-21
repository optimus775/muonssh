package muon.app.ui.components.session.utilpage.services;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.util.Locale;

import static muon.app.util.ScalingUtil.getScaledEmptyBorder;

public class ServiceTableCellRenderer extends JLabel implements TableCellRenderer {
    private static final Color STATUS_OK = new Color(0x43, 0xA0, 0x47);
    private static final Color STATUS_ERROR = new Color(0xE5, 0x39, 0x35);

    public ServiceTableCellRenderer() {
        setText("HHH");
        setBorder(getScaledEmptyBorder(5, 5, 5, 5));
        setOpaque(true);
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        String text = value == null ? "" : value.toString();
        setText(text);
        setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
        if (isSelected) {
            setForeground(table.getSelectionForeground());
            return this;
        }
        if (column == 2) {
            String normalized = text.toLowerCase(Locale.ENGLISH);
            if ("active(running)".equals(normalized)) {
                setForeground(STATUS_OK);
            } else if (normalized.contains("failed") || normalized.contains("error")) {
                setForeground(STATUS_ERROR);
            } else {
                setForeground(table.getForeground());
            }
        } else {
            setForeground(table.getForeground());
        }
        return this;
    }
}
