package muon.app.ui.components.session.files.ssh;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import muon.app.App;
import muon.app.common.FileInfo;
import muon.app.ui.components.session.files.FileBrowser;
import muon.app.util.FormatUtils;
import muon.app.util.PathUtils;
import muon.app.util.enums.FileType;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.format.DateTimeFormatter;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static muon.app.util.ScalingUtil.getScaledEmptyBorder;
import static muon.app.util.ScalingUtil.scale;


@Slf4j
public class PropertiesDialog extends JDialog {
    public static final int S_IRUSR = 00400; // read by owner
    public static final int S_IWUSR = 00200; // write by owner
    public static final int S_IXUSR = 00100; // execute/search by owner
    public static final int S_IRGRP = 00040; // read by group
    public static final int S_IWGRP = 00020; // write by group
    public static final int S_IXGRP = 00010; // execute/search by group
    public static final int S_IROTH = 00004; // read by others
    public static final int S_IWOTH = 00002; // write by others
    public static final int S_IXOTH = 00001; // execute/search by others
    public static final int S_ISUID = 04000; // Set user ID on execution
    public static final int S_ISGID = 02000; // Set group ID on execution
    public static final int S_ISVTX = 01000; // Sticky bit

    static final int[] PERMS = new int[]{S_IRUSR, S_IWUSR, S_IXUSR, S_IRGRP,
                                         S_IWGRP, S_IXGRP, S_IROTH, S_IWOTH, S_IXOTH, S_ISUID, S_ISGID, S_ISVTX};
    private static final Pattern DU_PATTERN = Pattern
            .compile("([\\d]+)\\s+(.+)");
    private static final Pattern DF_PATTERN = Pattern.compile(
            "[^\\s]+\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+%)\\s+[^\\s]+");
    private static final String GETENT_PASSWD_COMMAND = "getent passwd";
    private static final String GETENT_GROUP_COMMAND = "getent group";
    private final JCheckBox[] chkPermissons;
    private final JTextField txtSize;
    private final JTextField txtFreeSpace;
    private final FileBrowser fileBrowser;
    private final AtomicBoolean modified = new AtomicBoolean(false);
    private final JButton btnOK;
    private final JCheckBox chkRecursive;
    private final boolean multimode;

    @Getter
    private int dialogResult = JOptionPane.CANCEL_OPTION;
    private FileInfo[] details;
    private JTextField txtName;
    private JTextField txtType;
    private JTextField txtOwner;
    private JTextField txtGroup;
    private JTextField txtModified;
    private JTextField txtPath;
    private JTextField txtMode;
    private JTextField txtUid;
    private JTextField txtGid;
    private JTextField txtFileCount;
    private JButton btnCalculate1;
    private JButton btnCalculate2;
    private RemoteIdentityMap userIdentityMap = RemoteIdentityMap.EMPTY;
    private RemoteIdentityMap groupIdentityMap = RemoteIdentityMap.EMPTY;
    private boolean updatingFields;
    private boolean permissionsModified;
    private boolean ownerModified;
    private boolean groupModified;
    private int originalPermissions = -1;
    private int originalUid = -1;
    private int originalGid = -1;

    public PropertiesDialog(FileBrowser holder, Window window,
                            boolean multimode) {
        super(window);
        this.fileBrowser = holder;
        this.multimode = multimode;
        setResizable(true);
        setModal(true);
        setTitle("Properties");
        chkPermissons = new JCheckBox[12];
        for (int i = 0; i < 9; i++) {
            String[] labels = new String[]{"read", "write", "execute"};
            chkPermissons[i] = generateChkPermission(labels[i % 3]);
        }

        chkPermissons[9] = generateChkPermission("SUID");
        chkPermissons[10] = generateChkPermission("SGID");
        chkPermissons[11] = generateChkPermission("StickyBit");

        chkRecursive = new JCheckBox("Apply Recursive changes");

        chkRecursive.setAlignmentX(Box.LEFT_ALIGNMENT);
        chkRecursive.addActionListener(e -> updateButtonState());


        JLabel lblOwner = new JLabel("Owner permissions");
        lblOwner.setAlignmentX(Box.LEFT_ALIGNMENT);
        JLabel lblGroup = new JLabel("Group permissions");
        lblGroup.setAlignmentX(Box.LEFT_ALIGNMENT);
        JLabel lblOther = new JLabel("Other permissions");
        lblOther.setAlignmentX(Box.LEFT_ALIGNMENT);
        JLabel lblAdvance = new JLabel("Advance permissions");
        lblAdvance.setAlignmentX(Box.LEFT_ALIGNMENT);

        Box b = Box.createVerticalBox();

        JButton btnGetDiskSpaceUsed;
        if (multimode) {
            txtFileCount = new JTextField(30);
            b.add(addPropertyField(txtFileCount, "Total"));
            b.add(Box.createVerticalStrut(10));

            txtSize = new JTextField(30);
            Box boxSize = (Box) addPropertyField(txtSize, "Size");
            boxSize.add(Box.createVerticalGlue());
            btnCalculate1 = new JButton("Calculate");
            btnCalculate1.addActionListener(e -> calculateDirSize());
            btnCalculate1.setEnabled(false);
            boxSize.add(btnCalculate1);
            b.add(boxSize);
            b.add(Box.createVerticalStrut(10));

            txtFreeSpace = new JTextField(30);
            Box boxFree = (Box) addPropertyField(txtFreeSpace, "Free space");
            boxFree.add(Box.createVerticalGlue());
            btnGetDiskSpaceUsed = new JButton("Get free space");
            btnGetDiskSpaceUsed.addActionListener(e -> calculateFreeSpace());
            boxFree.add(btnGetDiskSpaceUsed);
            b.add(boxFree);
            b.add(Box.createVerticalStrut(10));

            txtOwner = new JTextField(30);
            b.add(addPropertyField(txtOwner, "Owner", true));
            b.add(Box.createVerticalStrut((10)));

            txtUid = new JTextField(30);
            b.add(addPropertyField(txtUid, "UID", true));
            b.add(Box.createVerticalStrut((10)));

            txtGroup = new JTextField(30);
            b.add(addPropertyField(txtGroup, "Group", true));
            b.add(Box.createVerticalStrut((10)));

            txtGid = new JTextField(30);
            b.add(addPropertyField(txtGid, "GID", true));
            b.add(Box.createVerticalStrut((10)));
        } else {
            txtName = new JTextField(30);
            b.add(addPropertyField(txtName, "Name"));
            b.add(Box.createVerticalStrut(10));

            txtPath = new JTextField(30);
            b.add(addPropertyField(txtPath, "Path"));
            b.add(Box.createVerticalStrut(10));

            txtSize = new JTextField(30);

            Box boxSize = (Box) addPropertyField(txtSize, "Size");
            boxSize.add(Box.createVerticalGlue());
            btnCalculate2 = new JButton("Calculate");
            btnCalculate2.addActionListener(e -> calculateDirSize());
            boxSize.add(btnCalculate2);
            btnCalculate2.setEnabled(false);

            b.add(boxSize);
            b.add(Box.createVerticalStrut(10));

            txtOwner = new JTextField(30);
            b.add(addPropertyField(txtOwner, "Owner", true));
            b.add(Box.createVerticalStrut((10)));

            txtUid = new JTextField(30);
            b.add(addPropertyField(txtUid, "UID", true));
            b.add(Box.createVerticalStrut((10)));

            txtType = new JTextField(30);
            b.add(addPropertyField(txtType, "Type"));
            b.add(Box.createVerticalStrut((10)));

            txtGroup = new JTextField(30);
            b.add(addPropertyField(txtGroup, "Group", true));
            b.add(Box.createVerticalStrut((10)));

            txtGid = new JTextField(30);
            b.add(addPropertyField(txtGid, "GID", true));
            b.add(Box.createVerticalStrut((10)));

            txtModified = new JTextField(30);
            b.add(addPropertyField(txtModified, "Last modified"));
            b.add(Box.createVerticalStrut((10)));

            txtFreeSpace = new JTextField(30);
            Box boxFree = (Box) addPropertyField(txtFreeSpace, "Free space");
            boxFree.add(Box.createVerticalGlue());
            btnGetDiskSpaceUsed = new JButton("Get free space");
            btnGetDiskSpaceUsed.addActionListener(e -> calculateFreeSpace());
            boxFree.add(btnGetDiskSpaceUsed);
            b.add(boxFree);
            b.add(Box.createVerticalStrut(10));
        }

        txtMode = new JTextField(30);
        b.add(addPropertyField(txtMode, "Mode", true));
        b.add(Box.createVerticalStrut(10));
        installFieldListeners();

        b.add(lblOwner);

        for (int i = 0; i < 3; i++) {
            b.add(chkPermissons[i]);
        }
        b.add(Box.createVerticalStrut((10)));
        b.add(lblGroup);
        for (int i = 3; i < 6; i++) {
            b.add(chkPermissons[i]);
        }
        b.add(Box.createVerticalStrut((10)));
        b.add(lblOther);
        for (int i = 6; i < 9; i++) {
            b.add(chkPermissons[i]);
        }

        b.add(Box.createVerticalStrut((10)));
        b.add(lblAdvance);
        for (int i = 9; i < 12; i++) {
            b.add(chkPermissons[i]);
        }

        b.add(Box.createVerticalStrut((10)));
        b.add(chkRecursive);

        Box b2 = Box.createHorizontalBox();
        btnOK = new JButton("Apply changes");
        btnOK.setEnabled(false);
        btnOK.addActionListener(e -> {
            FilePropertyChanges changes = getChanges();
            if (changes != null && changes.hasChanges()) {
                dialogResult = JOptionPane.OK_OPTION;
                applyChangesAsync(changes, details, chkRecursive.isSelected());
                dispose();
            }
        });
        JButton btnCancel = new JButton(App.getCONTEXT().getBundle().getString("cancel"));
        btnCancel.addActionListener(e -> {
            dialogResult = JOptionPane.CANCEL_OPTION;
            dispose();
        });
        b2.setAlignmentX(Box.LEFT_ALIGNMENT);
        b2.add(Box.createHorizontalGlue());
        b2.add(btnOK);
        b2.add(Box.createHorizontalStrut((10)));
        b2.add(btnCancel);
        b.add(Box.createVerticalGlue());

        int w = Math.max(btnOK.getPreferredSize().width,
                         btnCancel.getPreferredSize().width);
        btnOK.setPreferredSize(
                scale(new Dimension(w, btnOK.getPreferredSize().height)));
        btnCancel.setPreferredSize(
                scale(new Dimension(w, btnCancel.getPreferredSize().height)));

        b.setBorder(getScaledEmptyBorder((10), (10), (10), (10)));
        b2.setBorder(getScaledEmptyBorder((10), (10), (10), (10)));
        add(b);
        add(b2, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(App.getAppWindow());
    }

    private JCheckBox generateChkPermission(String label) {
        var chkPermission = new JCheckBox(label);
        chkPermission.setAlignmentX(Box.LEFT_ALIGNMENT);
        chkPermission.addActionListener(e -> {
            if (updatingFields) {
                return;
            }
            permissionsModified = true;
            updateButtonState();
            updateModeFromCheckboxes();
        });

        return chkPermission;
    }

    private boolean[] extractPermissions(int permissions) {
        return FilePropertyMode.permissionsToSelection(permissions);
    }

    public void setDetails(FileInfo details) {
        this.details = new FileInfo[1];
        this.details[0] = details;
        log.info("Extra: {}", details.getExtra());
        btnCalculate2.setEnabled(details.getType() == FileType.DIRECTORY
                                 || details.getType() == FileType.DIR_LINK);

        chkRecursive.setEnabled(details.getType() == FileType.DIRECTORY);
        chkRecursive.setForeground(UIManager.getColor(
                details.getType() != FileType.DIRECTORY ? "Label.disabledForeground" : "Label.foreground"));
        int permissions = details.getPermission();
        originalPermissions = permissions;
        originalUid = details.getUid();
        originalGid = details.getGid();
        updatingFields = true;
        try {
            txtOwner.setText(valueOrEmpty(details.getUser()));
            txtUid.setText(formatId(details.getUid()));
            txtGroup.setText(valueOrEmpty(details.getGroup()));
            txtGid.setText(formatId(details.getGid()));
            this.txtModified.setText(details.getLastModified()
                                             .format(DateTimeFormatter.ISO_DATE_TIME));
            this.txtName.setText(details.getName());
            this.txtPath.setText(details.getPath());
            this.txtSize.setText(details.getType() == FileType.DIRECTORY
                                 || details.getType() == FileType.DIR_LINK ? "---"
                                                                           : FormatUtils.humanReadableByteCount(details.getSize(),
                                                                                                                true));
            this.txtType.setText(details.getType() == FileType.DIRECTORY
                                 || details.getType() == FileType.DIR_LINK ? "Directory"
                                                                           : "File");
            setPermissionCheckboxes(permissions);
            txtMode.setText(FilePropertyMode.formatOctalMode(permissions));
        } finally {
            updatingFields = false;
        }
        permissionsModified = false;
        ownerModified = false;
        groupModified = false;
        modified.set(false);
        loadIdentityMapsAsync();
        updateButtonState();
    }

    public void setMultipleDetails(FileInfo[] files) {
        this.details = files;
        boolean hasAnyDir = false;
        long totalSize = 0;
        originalPermissions = commonPermissions(files);
        originalUid = commonUid(files);
        originalGid = commonGid(files);
        for (FileInfo file : files) {
            if (file.getType() == FileType.DIR_LINK
                || file.getType() == FileType.DIRECTORY) {
                hasAnyDir = true;
                break;
            }
        }
        if (!hasAnyDir) {
            for (FileInfo file : files) {
                if (file.getType() == FileType.FILE
                    || file.getType() == FileType.FILE_LINK) {
                    totalSize += file.getSize();
                }
            }
            txtSize.setText(
                    FormatUtils.humanReadableByteCount(totalSize, true));
        }
        btnCalculate1.setEnabled(hasAnyDir);
        chkRecursive.setEnabled(hasAnyDir);
        chkRecursive.setForeground(UIManager.getColor(
                hasAnyDir ? "Label.foreground" : "Label.disabledForeground"));
        updatingFields = true;
        try {
            txtOwner.setText(commonText(files, true));
            txtUid.setText(formatId(originalUid));
            txtGroup.setText(commonText(files, false));
            txtGid.setText(formatId(originalGid));
            if (originalPermissions >= 0) {
                setPermissionCheckboxes(originalPermissions);
                txtMode.setText(FilePropertyMode.formatOctalMode(originalPermissions));
            } else {
                txtMode.setText("");
                setPermissionCheckboxes(0);
            }
        } finally {
            updatingFields = false;
        }
        permissionsModified = false;
        ownerModified = false;
        groupModified = false;
        modified.set(false);
        int fc = 0;
        int dc = 0;
        for (FileInfo f : files) {
            if (f.getType() == FileType.DIRECTORY
                || f.getType() == FileType.DIR_LINK) {
                dc++;
            } else {
                fc++;
            }
        }
        txtFileCount.setText(fc + " files, " + dc + " folders");
        loadIdentityMapsAsync();
        updateButtonState();
    }

    private int commonPermissions(FileInfo[] files) {
        if (files.length == 0) {
            return -1;
        }
        int permissions = files[0].getPermission();
        for (FileInfo file : files) {
            if (file.getPermission() != permissions) {
                return -1;
            }
        }
        return permissions;
    }

    private int commonUid(FileInfo[] files) {
        if (files.length == 0 || files[0].getUid() < 0) {
            return -1;
        }
        int uid = files[0].getUid();
        for (FileInfo file : files) {
            if (file.getUid() != uid) {
                return -1;
            }
        }
        return uid;
    }

    private int commonGid(FileInfo[] files) {
        if (files.length == 0 || files[0].getGid() < 0) {
            return -1;
        }
        int gid = files[0].getGid();
        for (FileInfo file : files) {
            if (file.getGid() != gid) {
                return -1;
            }
        }
        return gid;
    }

    private String commonText(FileInfo[] files, boolean ownerField) {
        if (files.length == 0) {
            return "";
        }
        String value = valueOrEmpty(ownerField ? files[0].getUser() : files[0].getGroup());
        if (value.isEmpty()) {
            return "";
        }
        for (FileInfo file : files) {
            String next = valueOrEmpty(ownerField ? file.getUser() : file.getGroup());
            if (!value.equals(next)) {
                return "";
            }
        }
        return value;
    }

    public int getPermissions() {
        boolean[] selected = new boolean[chkPermissons.length];
        for (int i = 0; i < chkPermissons.length; i++) {
            selected[i] = chkPermissons[i].isSelected();
        }
        return FilePropertyMode.selectionToPermissions(selected);
    }

    private Component addPropertyField(JTextField txt, String label) {
        return addPropertyField(txt, label, false);
    }

    private Component addPropertyField(JTextField txt, String label, boolean editable) {
        txt.setEditable(editable);
        if (!editable) {
            txt.setBackground(App.getCONTEXT().getSkin().getDefaultBackground());
            txt.setBorder(null);
        }
        JLabel lblFileName = new JLabel(label);
        lblFileName.setPreferredSize(
                scale(new Dimension((150), lblFileName.getPreferredSize().height)));
        Box b11 = Box.createHorizontalBox();
        b11.setAlignmentX(Box.LEFT_ALIGNMENT);
        b11.add(lblFileName);
        b11.add(txt);
        return b11;
    }

    private void installFieldListeners() {
        addDocumentChangeListener(txtMode, this::onModeTextChanged);
        addDocumentChangeListener(txtOwner, () -> onIdentityNameChanged(txtOwner, txtUid, userIdentityMap, true));
        addDocumentChangeListener(txtUid, () -> onIdentityIdChanged(txtUid, txtOwner, userIdentityMap, true));
        addDocumentChangeListener(txtGroup, () -> onIdentityNameChanged(txtGroup, txtGid, groupIdentityMap, false));
        addDocumentChangeListener(txtGid, () -> onIdentityIdChanged(txtGid, txtGroup, groupIdentityMap, false));
    }

    private void addDocumentChangeListener(JTextField field, Runnable runnable) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                runnable.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                runnable.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                runnable.run();
            }
        });
    }

    private void onModeTextChanged() {
        if (updatingFields) {
            return;
        }
        permissionsModified = true;
        modified.set(true);
        OptionalInt mode = FilePropertyMode.parseOctalMode(txtMode.getText());
        if (mode.isPresent()) {
            setPermissionCheckboxes(mode.getAsInt());
        }
        updateButtonState();
    }

    private void onIdentityNameChanged(JTextField nameField, JTextField idField, RemoteIdentityMap identityMap,
                                       boolean ownerField) {
        if (updatingFields) {
            return;
        }
        modified.set(true);
        markIdentityModified(ownerField);
        String name = nameField.getText().trim();
        updatingFields = true;
        try {
            if (!name.isEmpty()) {
                Integer id = identityMap.getId(name);
                idField.setText(id == null ? "" : String.valueOf(id));
            }
        } finally {
            updatingFields = false;
        }
        updateButtonState();
    }

    private void onIdentityIdChanged(JTextField idField, JTextField nameField, RemoteIdentityMap identityMap,
                                     boolean ownerField) {
        if (updatingFields) {
            return;
        }
        modified.set(true);
        markIdentityModified(ownerField);
        OptionalInt id = parseNonNegativeId(idField.getText());
        updatingFields = true;
        try {
            if (id.isPresent()) {
                String name = identityMap.getName(id.getAsInt());
                nameField.setText(name == null ? "" : name);
            }
        } finally {
            updatingFields = false;
        }
        updateButtonState();
    }

    private void markIdentityModified(boolean ownerField) {
        if (ownerField) {
            ownerModified = true;
        } else {
            groupModified = true;
        }
    }

    private void setPermissionCheckboxes(int permissions) {
        boolean wasUpdating = updatingFields;
        updatingFields = true;
        try {
            boolean[] perms = extractPermissions(permissions);
            for (int i = 0; i < chkPermissons.length; i++) {
                chkPermissons[i].setSelected(perms[i]);
            }
        } finally {
            updatingFields = wasUpdating;
        }
    }

    private void updateModeFromCheckboxes() {
        boolean wasUpdating = updatingFields;
        updatingFields = true;
        try {
            txtMode.setText(FilePropertyMode.formatOctalMode(getPermissions()));
        } finally {
            updatingFields = wasUpdating;
        }
        updateButtonState();
    }

    private void loadIdentityMapsAsync() {
        fileBrowser.getHolder().EXECUTOR.submit(() -> {
            RemoteIdentityMap users = RemoteIdentityMap.EMPTY;
            RemoteIdentityMap groups = RemoteIdentityMap.EMPTY;
            try {
                StringBuilder output = new StringBuilder();
                if (fileBrowser.getSessionInstance().exec(GETENT_PASSWD_COMMAND,
                                                           new AtomicBoolean(false), output,
                                                           new StringBuilder()) == 0) {
                    users = RemoteIdentityMap.parsePasswd(output.toString());
                }

                output = new StringBuilder();
                if (fileBrowser.getSessionInstance().exec(GETENT_GROUP_COMMAND,
                                                           new AtomicBoolean(false), output,
                                                           new StringBuilder()) == 0) {
                    groups = RemoteIdentityMap.parseGroup(output.toString());
                }
            } catch (Exception e) {
                log.debug("Failed to load remote users/groups", e);
            }

            RemoteIdentityMap loadedUsers = users;
            RemoteIdentityMap loadedGroups = groups;
            SwingUtilities.invokeLater(() -> {
                if (!isDisplayable()) {
                    return;
                }
                userIdentityMap = loadedUsers;
                groupIdentityMap = loadedGroups;
                syncIdentityFieldsFromMaps();
                updateButtonState();
            });
        });
    }

    private void syncIdentityFieldsFromMaps() {
        updatingFields = true;
        try {
            syncIdentityFieldFromMap(txtOwner, txtUid, userIdentityMap);
            syncIdentityFieldFromMap(txtGroup, txtGid, groupIdentityMap);
        } finally {
            updatingFields = false;
        }
    }

    private void syncIdentityFieldFromMap(JTextField nameField, JTextField idField, RemoteIdentityMap identityMap) {
        OptionalInt id = parseNonNegativeId(idField.getText());
        if (id.isPresent()) {
            String mappedName = identityMap.getName(id.getAsInt());
            if (mappedName != null) {
                nameField.setText(mappedName);
            }
            return;
        }

        String name = nameField.getText().trim();
        if (!name.isEmpty()) {
            Integer mappedId = identityMap.getId(name);
            if (mappedId != null) {
                idField.setText(String.valueOf(mappedId));
            }
        }
    }

    private OptionalInt parseNonNegativeId(String text) {
        if (text == null || text.trim().isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            int id = Integer.parseInt(text.trim());
            return id < 0 ? OptionalInt.empty() : OptionalInt.of(id);
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String formatId(int id) {
        return id < 0 ? "" : String.valueOf(id);
    }

    private void calculateDirSize() {
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        JDialog dlg = new JDialog(this);
        dlg.setModal(true);
        JLabel lbl = new JLabel("Calculating...");
        lbl.setBorder(getScaledEmptyBorder(10, 10, 10, 10));
        dlg.add(lbl);
        dlg.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                lbl.setText("Cancelling...");
                stopFlag.set(true);
            }
        });
        dlg.pack();
        AtomicBoolean disposed = new AtomicBoolean(false);
        dlg.setLocationRelativeTo(this);
        calcSize(details, (a, b) -> SwingUtilities.invokeLater(() -> {
            dlg.dispose();
            disposed.set(true);
            log.info("Total size: {}", a);
            if (Boolean.TRUE.equals(b)) {
                txtSize.setText(
                        FormatUtils.humanReadableByteCount(a, true));
            }
        }), stopFlag);
        if (!disposed.get()) {
            dlg.setVisible(true);
        }
    }

    private void calculateFreeSpace() {
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        JDialog dlg = new JDialog(this);
        dlg.setModal(true);
        JLabel lbl = new JLabel("Calculating...");
        lbl.setBorder(getScaledEmptyBorder(10, 10, 10, 10));
        dlg.add(lbl);
        dlg.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                lbl.setText("Cancelling...");
                stopFlag.set(true);
            }
        });
        dlg.pack();
        AtomicBoolean disposed = new AtomicBoolean(false);
        dlg.setLocationRelativeTo(this);
        calcFreeSpace(details, (a, b) -> SwingUtilities.invokeLater(() -> {
            dlg.dispose();
            disposed.set(true);
            log.info("Total size: {}", a);
            if (Boolean.TRUE.equals(b)) {
                txtFreeSpace.setText(a);
            }
        }), stopFlag);
        if (!disposed.get()) {
            dlg.setVisible(true);
        }
    }

    public void calcSize(FileInfo[] files, BiConsumer<Long, Boolean> biConsumer,
                         AtomicBoolean stopFlag) {
        StringBuilder command = new StringBuilder();
        command.append(
                "export POSIXLY_CORRECT=1; export BLOCKSIZE=512; du -s ");
        for (FileInfo fileInfo : files) {
            command.append("\"").append(fileInfo.getPath()).append("\" ");
        }
        log.info("Command to execute: {}", command);
        fileBrowser.getHolder().EXECUTOR.submit(() -> {
            try {
                long total = 0;
                StringBuilder output = new StringBuilder();
                boolean ret = fileBrowser.getSessionInstance().exec(
                        command.toString(), stopFlag, output,
                        new StringBuilder()) == 0;
                if (stopFlag.get()) {
                    biConsumer.accept(0L, false);
                    return;
                }
                if (!ret && !fileBrowser.isSessionClosed()) {
                    JOptionPane.showMessageDialog(null, App.getCONTEXT().getBundle().getString("operation_errors")
                                                 );
                }

                for (String line : output.toString().split("\n")) {
                    Matcher matcher = DU_PATTERN.matcher(line.trim());
                    if (matcher.find()) {
                        total += Long.parseLong(matcher.group(1).trim()) * 512;
                    }
                }
                biConsumer.accept(total, true);
                return;
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
            biConsumer.accept(-1L, false);
        });
    }

    public void calcFreeSpace(FileInfo[] files,
                              BiConsumer<String, Boolean> biConsumer, AtomicBoolean stopFlag) {
        StringBuilder command = new StringBuilder();
        command.append("export POSIXLY_CORRECT=1; export BLOCKSIZE=1024; df -P -k \"").append(files[0].getPath()).append("\"");
        log.info("Command to execute: {}", command);
        fileBrowser.getHolder().EXECUTOR.submit(() -> {
            try {
                StringBuilder output = new StringBuilder();
                boolean ret = fileBrowser.getSessionInstance().exec(
                        command.toString(), stopFlag, output,
                        new StringBuilder()) == 0;
                log.info(output.toString());
                if (stopFlag.get()) {
                    log.info("stop flag");
                    biConsumer.accept(null, false);
                    return;
                }
                if (!ret && !fileBrowser.isSessionClosed()) {
                    JOptionPane.showMessageDialog(null,
                                                  App.getCONTEXT().getBundle().getString("operation_errors"));
                }


                String[] lines = output.toString().split("\n");
                if (lines.length >= 2) {
                    Matcher matcher = DF_PATTERN.matcher(lines[1]);
                    if (matcher.find()) {
                        long total = Long.parseLong(matcher.group(1).trim())
                                     * 1024;
                        long free = Long.parseLong(matcher.group(3).trim())
                                    * 1024;
                        long freePct = 100 - Long.parseLong(
                                matcher.group(4).replace("%", "").trim());
                        String result = String.format("Free %s of %s (%s)",
                                                      FormatUtils.humanReadableByteCount(free, true),
                                                      FormatUtils.humanReadableByteCount(total, true),
                                                      freePct + "%");
                        biConsumer.accept(result, true);
                        return;
                    } else {
                        log.info(
                                "Did not match with [^\\s]+\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+%)\\s[^\\s+]+");
                    }
                }
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
            biConsumer.accept(null, false);
        });
    }

    private FilePropertyChanges getChanges() {
        OptionalInt mode = FilePropertyMode.parseOctalMode(txtMode.getText());
        if ((!multimode || permissionsModified) && mode.isEmpty()) {
            return null;
        }

        FilePropertyChanges changes = new FilePropertyChanges();
        if (multimode) {
            if (permissionsModified && mode.isPresent()) {
                if (originalPermissions < 0 || mode.getAsInt() != originalPermissions) {
                    changes.permissions = mode.getAsInt();
                }
            }
            return applyIdentityChanges(changes) ? changes : null;
        }

        if (mode.isPresent() && mode.getAsInt() != originalPermissions) {
            changes.permissions = mode.getAsInt();
        }

        return applyIdentityChanges(changes) ? changes : null;
    }

    private boolean applyIdentityChanges(FilePropertyChanges changes) {
        if (ownerModified) {
            RemoteIdentityMap.Resolution owner = RemoteIdentityMap.resolve(txtOwner.getText(), txtUid.getText(),
                                                                           userIdentityMap);
            if (!owner.isValid()) {
                return false;
            }
            if (owner.getId() != null && (originalUid < 0 || owner.getId() != originalUid)) {
                changes.uid = owner.getId();
            }
        }

        if (groupModified) {
            RemoteIdentityMap.Resolution group = RemoteIdentityMap.resolve(txtGroup.getText(), txtGid.getText(),
                                                                           groupIdentityMap);
            if (!group.isValid()) {
                return false;
            }
            if (group.getId() != null && (originalGid < 0 || group.getId() != originalGid)) {
                changes.gid = group.getId();
            }
        }

        return true;
    }

    private void applyChangesAsync(FilePropertyChanges changes, FileInfo[] paths, boolean isUpdateRecursive) {
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        JDialog dlg = new JDialog(this);
        dlg.setModal(true);
        JLabel lbl = new JLabel("Applying...");
        lbl.setBorder(getScaledEmptyBorder(10, 10, 10, 10));
        dlg.add(lbl);
        dlg.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                lbl.setText("Cancelling...");
                stopFlag.set(true);
            }
        });
        dlg.pack();
        AtomicBoolean disposed = new AtomicBoolean(false);
        dlg.setLocationRelativeTo(this);
        fileBrowser.getHolder().EXECUTOR.submit(() -> {
            try {
                for (FileInfo path : paths) {
                    applyChangesRecursive(changes, path.getPath(), isUpdateRecursive && path.isDirectory(), stopFlag);
                    log.info("Properties changed");
                }
                modified.set(true);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                if (!fileBrowser.isSessionClosed()) {
                    JOptionPane.showMessageDialog(null, App.getCONTEXT().getBundle().getString("operation_failed"));
                }
            }
            SwingUtilities.invokeLater(() -> {
                dlg.dispose();
                disposed.set(true);
                updateButtonState();
            });
        });

        if (!disposed.get()) {
            dlg.setVisible(true);
        }
    }

    private void updateButtonState() {
        if (btnOK == null) {
            return;
        }
        FilePropertyChanges changes = getChanges();
        btnOK.setEnabled(changes != null && changes.hasChanges());
    }

    private void applyChangesRecursive(FilePropertyChanges changes, String path, boolean isUpdateRecursive,
                                       AtomicBoolean stopFlag) throws Exception {
        if (stopFlag.get()) {
            return;
        }

        applyChangesToPath(changes, path);
        invalidateDirectoryCache(path);

        if (!isUpdateRecursive) {
            return;
        }

        for (var item : fileBrowser.getSSHFileSystem().list(path)) {
            if (stopFlag.get()) {
                return;
            }
            String childPath = item.getPath();

            // Skip "." and ".."
            if (item.getName().equals(".") || item.getName().equals("..")) {
                continue;
            }
            log.debug(childPath);

            if (item.isDirectory()) {
                applyChangesRecursive(changes, childPath, true, stopFlag);
            } else {
                applyChangesToPath(changes, childPath);
                invalidateDirectoryCache(childPath);
            }
        }
    }

    private void applyChangesToPath(FilePropertyChanges changes, String path) throws Exception {
        if (changes.uid != null) {
            fileBrowser.getSSHFileSystem().chown(path, changes.uid);
        }
        if (changes.gid != null) {
            fileBrowser.getSSHFileSystem().chgrp(path, changes.gid);
        }
        if (changes.permissions != null) {
            fileBrowser.getSSHFileSystem().chmod(changes.permissions, path);
        }
    }

    private void invalidateDirectoryCache(String path) {
        fileBrowser.getSSHDirectoryCache().remove(path);
        String parent = PathUtils.getParent(path);
        if (parent != null) {
            fileBrowser.getSSHDirectoryCache().remove(parent);
            if (parent.endsWith("/") && parent.length() > 1) {
                fileBrowser.getSSHDirectoryCache().remove(parent.substring(0, parent.length() - 1));
            }
        }
    }

    private static class FilePropertyChanges {
        private Integer permissions;
        private Integer uid;
        private Integer gid;

        private boolean hasChanges() {
            return permissions != null || uid != null || gid != null;
        }
    }
}
