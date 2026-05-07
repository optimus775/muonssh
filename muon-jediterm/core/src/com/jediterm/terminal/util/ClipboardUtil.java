package com.jediterm.terminal.util;

import java.awt.*;
import java.awt.datatransfer.StringSelection;

public final class ClipboardUtil {
  private ClipboardUtil() {
  }

  public static void copyToAllClipboards(String text) {
    if (text == null || text.isEmpty()) {
      return;
    }
    StringSelection sel = new StringSelection(text);

    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);

    try {
      Toolkit tk = Toolkit.getDefaultToolkit();
      var m = tk.getClass().getMethod("getSystemSelection");
      Object selection = m.invoke(tk);
      if (selection instanceof java.awt.datatransfer.Clipboard) {
        ((java.awt.datatransfer.Clipboard) selection).setContents(sel, null);
      }
    }
    catch (Exception ignored) {
      // PRIMARY not available, safe to ignore
    }
  }
}
