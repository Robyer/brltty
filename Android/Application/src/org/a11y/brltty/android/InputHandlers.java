/*
 * BRLTTY - A background process providing access to the console screen (when in
 *          text mode) for a blind person using a refreshable braille display.
 *
 * Copyright (C) 1995-2018 by The BRLTTY Developers.
 *
 * BRLTTY comes with ABSOLUTELY NO WARRANTY.
 *
 * This is free software, placed under the terms of the
 * GNU Lesser General Public License, as published by the Free Software
 * Foundation; either version 2.1 of the License, or (at your option) any
 * later version. Please see the file LICENSE-LGPL for details.
 *
 * Web Page: http://brltty.com/
 *
 * This software is maintained by Dave Mielke <dave@mielke.cc>.
 */

package org.a11y.brltty.android;

import java.util.Arrays;
import java.util.Comparator;

import java.util.Collections;
import java.util.List;

import android.os.Bundle;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import android.view.inputmethod.InputConnection;
import android.view.KeyEvent;

import android.text.Editable;
import android.text.SpannableStringBuilder;

public abstract class InputHandlers {
  private InputHandlers () {
  }

  private final static int NO_SELECTION = -1;

  private static boolean performGlobalAction (int action) {
    return BrailleService.getBrailleService().performGlobalAction(action);
  }

  private static AccessibilityNodeInfo getCursorNode () {
    RenderedScreen screen = ScreenDriver.getScreen();
    if (screen == null) return null;
    return screen.getCursorNode();
  }

  private static List<AccessibilityWindowInfo> getVisibleWindows () {
    if (ApplicationUtilities.haveLollipop) {
      return BrailleService.getBrailleService().getWindows();
    } else {
      return Collections.EMPTY_LIST;
    }
  }

  private static AccessibilityWindowInfo[] getVisibleWindows (final Comparator<Integer> comparator) {
    List<AccessibilityWindowInfo> list = getVisibleWindows();
    AccessibilityWindowInfo[] array = list.toArray(new AccessibilityWindowInfo[list.size()]);

    Arrays.sort(array,
      new Comparator<AccessibilityWindowInfo>() {
        @Override
        public int compare (AccessibilityWindowInfo window1, AccessibilityWindowInfo window2) {
          return comparator.compare(window1.getId(), window2.getId());
        }
      }
    );

    return array;
  }

  private static boolean switchToWindow (AccessibilityWindowInfo window) {
    AccessibilityNodeInfo rootNode = window.getRoot();

    if (rootNode != null) {
      try {
        AccessibilityNodeInfo focusableNode = ScreenUtilities.findFocusableNode(rootNode);

        if (focusableNode != null) {
          try {
            if (focusableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
              focusableNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
              return true;
            }
          } finally {
            focusableNode.recycle();
            focusableNode = null;
          }
        }
      } finally {
        rootNode.recycle();
        rootNode = null;
      }
    }

    return false;
  }

  private static boolean switchToWindow (Comparator<Integer> comparator) {
    boolean found = false;
    AccessibilityNodeInfo cursorNode = getCursorNode();

    if (cursorNode != null) {
      try {
        int referenceIdentifier = cursorNode.getWindowId();
        for (AccessibilityWindowInfo window : getVisibleWindows(comparator)) {
          try {
            if (!found) {
              if (comparator.compare(window.getId(), referenceIdentifier) > 0) {
                if (switchToWindow(window)) {
                  found = true;
                }
              }
            }
          } finally {
            window.recycle();
            window = null;
          }
        }
      } finally {
        cursorNode.recycle();
        cursorNode = null;
      }
    }

    return found;
  }

  private static boolean moveFocus (RenderedScreen.SearchDirection direction) {
    RenderedScreen screen = ScreenDriver.getScreen();

    if (screen != null) {
      if (screen.moveFocus(direction)) {
        return true;
      }
    }

    return false;
  }

  private static int getTextStartOffset (AccessibilityNodeInfo node) {
    int offset = node.getTextSelectionStart();
    if (offset == NO_SELECTION) return 0;
    return offset;
  }

  private static int getTextEndOffset (AccessibilityNodeInfo node) {
    int offset = node.getTextSelectionEnd();
    if (offset == NO_SELECTION) return 0;
    if (offset != getTextStartOffset(node)) offset -= 1;
    return offset;
  }

  private static Integer findNextLine (CharSequence text, int offset) {
    int length = text.length();

    while (offset < length) {
      if (text.charAt(offset++) == '\n') return offset;
    }

    return null;
  }

  private static int findCurrentLine (CharSequence text, int offset) {
    while (offset > 0) {
      if (text.charAt(--offset) == '\n') {
        offset += 1;
        break;
      }
    }

    return offset;
  }

  private static Integer findPreviousLine (CharSequence text, int offset) {
    offset = findCurrentLine(text, offset);
    if (offset == 0) return null;
    return findCurrentLine(text, offset-1);
  }

  public static boolean setSelection (AccessibilityNodeInfo node, int start, int end) {
    {
      InputConnection connection = InputService.getInputConnection();
      if (connection != null) return connection.setSelection(start, end);
    }

    if (ApplicationUtilities.haveJellyBeanMR2) {
      Bundle arguments = new Bundle();
      arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
      arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
      return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments);
    }

    return false;
  }

  public static boolean placeCursor (AccessibilityNodeInfo node, int offset) {
    return setSelection(node, offset, offset);
  }

  private abstract static class TextEditor {
    public TextEditor () {
    }

    protected abstract boolean editText (InputConnection connection);
    protected abstract Integer editText (Editable editor, int start, int end);

    private final boolean editText (AccessibilityNodeInfo node) {
      if (node.isFocused()) {
        CharSequence text = node.getText();
        int start = node.getTextSelectionStart();
        int end = node.getTextSelectionEnd();

        if (start == NO_SELECTION) text = null;
        if (text == null) text = "";
        if (text.length() == 0) start = end = 0;

        Editable editor = (text instanceof Editable)?
                          (Editable)text:
                          new SpannableStringBuilder(text);

        if ((0 <= start) && (start <= end) && (end <= text.length())) {
          Integer offset = editText(editor, start, end);

          if (offset != null) {
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, editor);

            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
              if (offset == editor.length()) return true;
              return placeCursor(node, offset);
            }
          }
        }
      }

      return false;
    }

    public final boolean editText () {
      {
        InputConnection connection = InputService.getInputConnection();
        if (connection != null) return editText(connection);
      }

      if (ApplicationUtilities.haveLollipop) {
        AccessibilityNodeInfo node = getCursorNode();

        if (node != null) {
          try {
            if (node.isEditable()) {
              if (!node.isPassword()) {
                return editText(node);
              }
            }
          } finally {
            node.recycle();
            node = null;
          }
        }
      }

      return false;
    }
  }

  public static boolean inputCharacter (final char character) {
    return new TextEditor() {
      @Override
      protected boolean editText (InputConnection connection) {
        return connection.commitText(Character.toString(character), 1);
      }

      @Override
      protected Integer editText (Editable editor, int start, int end) {
        editor.replace(start, end, Character.toString(character));
        return start + 1;
      }
    }.editText();
  }

  private static boolean injectKey (int code) {
    return InputService.injectKey(code);
  }

  private abstract static class KeyHandler {
    private final int keyCode;

    public KeyHandler (int code) {
      keyCode = code;
    }

    protected boolean performNavigationAction (AccessibilityNodeInfo node) {
      throw new UnsupportedOperationException();
    }

    protected boolean performEditAction (AccessibilityNodeInfo node) {
      return performNavigationAction(node);
    }

    public final boolean handleKey () {
      try {
        AccessibilityNodeInfo node = getCursorNode();

        if (node != null) {
          try {
            return node.isEditable()?
                   performEditAction(node):
                   performNavigationAction(node);
          } finally {
            node.recycle();
            node = null;
          }
        }

        throw new UnsupportedOperationException();
      } catch (UnsupportedOperationException exception) {
        return injectKey(keyCode);
      }
    }
  }

  public static boolean inputKey_enter () {
    return new KeyHandler(KeyEvent.KEYCODE_ENTER) {
      @Override
      protected boolean performNavigationAction (AccessibilityNodeInfo node) {
        if (ApplicationUtilities.haveJellyBean) {
          return ScreenUtilities.performClick(node);
        }

        return super.performNavigationAction(node);
      }

      @Override
      protected boolean performEditAction (AccessibilityNodeInfo node) {
        if (ApplicationUtilities.haveLollipop) {
          return inputCharacter('\n');
        }

        return super.performEditAction(node);
      }
    }.handleKey();
  }

  public static boolean inputKey_tab () {
    return new KeyHandler(KeyEvent.KEYCODE_TAB) {
      @Override
      protected boolean performNavigationAction (AccessibilityNodeInfo node) {
        return moveFocus(RenderedScreen.SearchDirection.FORWARD);
      }
    }.handleKey();
  }

  public static boolean inputKey_backspace () {
    return new TextEditor() {
      @Override
      protected boolean editText (InputConnection connection) {
        return connection.deleteSurroundingText(1, 0);
      }

      @Override
      protected Integer editText (Editable editor, int start, int end) {
        if (start == end) {
          if (start < 1) return null;
          start -= 1;
        }

        editor.delete(start, end);
        return start;
      }
    }.editText();
  }

  public static boolean inputKey_escape () {
    if (ApplicationUtilities.haveJellyBean) {
      return performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    return injectKey(KeyEvent.KEYCODE_ESCAPE);
  }

  public static boolean inputKey_cursorLeft () {
    return new KeyHandler(KeyEvent.KEYCODE_DPAD_LEFT) {
      @Override
      protected boolean performNavigationAction (AccessibilityNodeInfo node) {
        return super.performNavigationAction(node);
      }

      @Override
      protected boolean performEditAction (AccessibilityNodeInfo node) {
        if (ApplicationUtilities.haveJellyBeanMR2) {
          int offset = node.getTextSelectionStart();
          if (offset == NO_SELECTION) return false;
          if (offset == node.getTextSelectionEnd()) offset -= 1;
          if (offset < 0) return false;
          return placeCursor(node, offset);
        }

        return super.performEditAction(node);
      }
    }.handleKey();
  }

  public static boolean inputKey_cursorRight () {
    return new KeyHandler(KeyEvent.KEYCODE_DPAD_RIGHT) {
      @Override
      protected boolean performNavigationAction (AccessibilityNodeInfo node) {
        return super.performNavigationAction(node);
      }

      @Override
      protected boolean performEditAction (AccessibilityNodeInfo node) {
        if (ApplicationUtilities.haveJellyBeanMR2) {
          int offset = node.getTextSelectionEnd();
          if (offset == NO_SELECTION) return false;
          if (offset == node.getTextSelectionStart()) offset += 1;
          if (offset > node.getText().length()) return false;
          return placeCursor(node, offset);
        }

        return super.performEditAction(node);
      }
    }.handleKey();
  }

  public static boolean inputKey_cursorUp () {
    return new KeyHandler(KeyEvent.KEYCODE_DPAD_UP) {
      @Override
      protected boolean performNavigationAction (AccessibilityNodeInfo node) {
        return super.performNavigationAction(node);
      }

      @Override
      protected boolean performEditAction (AccessibilityNodeInfo node) {
        if (ApplicationUtilities.haveJellyBeanMR2) {
          int offset = getTextStartOffset(node);

          CharSequence text = node.getText();
          int current = findCurrentLine(text, offset);
          if (current == 0) return false;

          int end = current - 1;
          int previous = findCurrentLine(text, end);

          int position = Math.min(offset-current, end-previous);
          return placeCursor(node, previous+position);
        }

        return super.performEditAction(node);
      }
    }.handleKey();
  }

  public static boolean inputKey_cursorDown () {
    return new KeyHandler(KeyEvent.KEYCODE_DPAD_DOWN) {
      @Override
      protected boolean performNavigationAction (AccessibilityNodeInfo node) {
        return super.performNavigationAction(node);
      }

      @Override
      protected boolean performEditAction (AccessibilityNodeInfo node) {
        if (ApplicationUtilities.haveJellyBeanMR2) {
          int offset = getTextEndOffset(node);

          CharSequence text = node.getText();
          Integer next = findNextLine(text, offset);
          if (next == null) return false;

          int current = findCurrentLine(text, offset);
          int position = offset - current;

          current = next;
          next = findNextLine(text, current);

          int end = ((next != null)? next-1: text.length()) - current;
          if (position > end) position = end;
          return placeCursor(node, current+position);
        }

        return super.performEditAction(node);
      }
    }.handleKey();
  }

  public static boolean inputKey_pageUp () {
    return new KeyHandler(KeyEvent.KEYCODE_PAGE_UP) {
      @Override
      protected boolean performNavigationAction (AccessibilityNodeInfo node) {
        if (ApplicationUtilities.haveJellyBean) {
          return ScreenUtilities.performScrollBackward(node);
        }

        return super.performNavigationAction(node);
      }

      @Override
      protected boolean performEditAction (AccessibilityNodeInfo node) {
        if (ApplicationUtilities.haveJellyBeanMR2) {
          int from = getTextStartOffset(node);

          final int to = 0;
          if (to == from) return false;

          return placeCursor(node, to);
        }

        return super.performEditAction(node);
      }
    }.handleKey();
  }

  public static boolean inputKey_pageDown () {
    return new KeyHandler(KeyEvent.KEYCODE_PAGE_DOWN) {
      @Override
      protected boolean performNavigationAction (AccessibilityNodeInfo node) {
        if (ApplicationUtilities.haveJellyBean) {
          return ScreenUtilities.performScrollForward(node);
        }

        return super.performNavigationAction(node);
      }

      @Override
      protected boolean performEditAction (AccessibilityNodeInfo node) {
        if (ApplicationUtilities.haveJellyBeanMR2) {
          int from = getTextEndOffset(node);

          final int to = node.getText().length();
          if (to == from) return false;

          return placeCursor(node, to);
        }

        return super.performEditAction(node);
      }
    }.handleKey();
  }

  public static boolean inputKey_home () {
    return new KeyHandler(KeyEvent.KEYCODE_MOVE_HOME) {
      @Override
      protected boolean performNavigationAction (AccessibilityNodeInfo node) {
        return moveFocus(RenderedScreen.SearchDirection.FIRST);
      }

      @Override
      protected boolean performEditAction (AccessibilityNodeInfo node) {
        if (ApplicationUtilities.haveJellyBeanMR2) {
          int from = getTextStartOffset(node);
          if (from == 0) return false;

          CharSequence text = node.getText();
          int to = findCurrentLine(text, from);
          if (to == from) return false;

          return placeCursor(node, to);
        }

        return super.performEditAction(node);
      }
    }.handleKey();
  }

  public static boolean inputKey_end () {
    return new KeyHandler(KeyEvent.KEYCODE_MOVE_END) {
      @Override
      protected boolean performNavigationAction (AccessibilityNodeInfo node) {
        return moveFocus(RenderedScreen.SearchDirection.LAST);
      }

      @Override
      protected boolean performEditAction (AccessibilityNodeInfo node) {
        if (ApplicationUtilities.haveJellyBeanMR2) {
          int from = getTextEndOffset(node);

          CharSequence text = node.getText();
          Integer next = findNextLine(text, from);

          int to = (next != null)? next-1: text.length();
          if (from == to) return false;

          return placeCursor(node, to);
        }

        return super.performEditAction(node);
      }
    }.handleKey();
  }

  public static boolean inputKey_insert () {
    return new KeyHandler(KeyEvent.KEYCODE_INSERT) {
      @Override
      protected boolean performNavigationAction (AccessibilityNodeInfo node) {
        if (ApplicationUtilities.haveJellyBean) {
          return ScreenUtilities.performLongClick(node);
        }

        return super.performNavigationAction(node);
      }
    }.handleKey();
  }

  public static boolean inputKey_delete () {
    return new TextEditor() {
      @Override
      protected boolean editText (InputConnection connection) {
        return connection.deleteSurroundingText(0, 1);
      }

      @Override
      protected Integer editText (Editable editor, int start, int end) {
        if (start == end) {
          if (end == editor.length()) return null;
          end += 1;
        }

        editor.delete(start, end);
        return start;
      }
    }.editText();
  }

  private interface FunctionKeyAction {
    public boolean performAction ();
  }

  private final static FunctionKeyAction functionKeyAction_backButton =
    new FunctionKeyAction() {
      @Override
      public boolean performAction () {
        if (ApplicationUtilities.haveJellyBean) {
          return performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        }

        return false;
      }
    };

  private final static FunctionKeyAction functionKeyAction_homeScreen =
    new FunctionKeyAction() {
      @Override
      public boolean performAction () {
        if (ApplicationUtilities.haveJellyBean) {
          return performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }

        return false;
      }
    };

  private final static FunctionKeyAction functionKeyAction_logScreen =
    new FunctionKeyAction() {
      @Override
      public boolean performAction () {
        ScreenLogger.log();
        return true;
      }
    };

  private final static FunctionKeyAction functionKeyAction_moveBackward =
    new FunctionKeyAction() {
      @Override
      public boolean performAction () {
        return moveFocus(RenderedScreen.SearchDirection.BACKWARD);
      }
    };

  private final static FunctionKeyAction functionKeyAction_moveForward =
    new FunctionKeyAction() {
      @Override
      public boolean performAction () {
        return moveFocus(RenderedScreen.SearchDirection.FORWARD);
      }
    };

  private final static FunctionKeyAction functionKeyAction_notificationsShade =
    new FunctionKeyAction() {
      @Override
      public boolean performAction () {
        if (ApplicationUtilities.haveJellyBean) {
          return performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
        }

        return false;
      }
    };

  private final static FunctionKeyAction functionKeyAction_optionsMenu =
    new FunctionKeyAction() {
      @Override
      public boolean performAction () {
        return new KeyHandler(KeyEvent.KEYCODE_MENU) {
        }.handleKey();
      }
    };

  private final static FunctionKeyAction functionKeyAction_powerDialog =
    new FunctionKeyAction() {
      @Override
      public boolean performAction () {
        if (ApplicationUtilities.haveLollipop) {
          return performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
        }

        return false;
      }
    };

  private final static FunctionKeyAction functionKeyAction_quickSettings =
    new FunctionKeyAction() {
      @Override
      public boolean performAction () {
        if (ApplicationUtilities.haveJellyBeanMR1) {
          return performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
        }

        return false;
      }
    };

  private final static FunctionKeyAction functionKeyAction_recentApplications =
    new FunctionKeyAction() {
      @Override
      public boolean performAction () {
        if (ApplicationUtilities.haveJellyBean) {
          return performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
        }

        return false;
      }
    };

  private final static FunctionKeyAction functionKeyAction_serviceSettings =
    new FunctionKeyAction() {
      @Override
      public boolean performAction () {
        return BrailleService.getBrailleService().launchSettingsActivity();
      }
    };

  private final static FunctionKeyAction functionKeyAction_previousWindow =
    new FunctionKeyAction() {
      @Override
      public boolean performAction () {
        Comparator<Integer> comparator =
          new Comparator<Integer>() {
            @Override
            public int compare (Integer id1, Integer id2) {
              return -Integer.compare(id1, id2);
            }
          };

        return switchToWindow(comparator);
      }
    };

  private final static FunctionKeyAction functionKeyAction_nextWindow =
    new FunctionKeyAction() {
      @Override
      public boolean performAction () {
        Comparator<Integer> comparator =
          new Comparator<Integer>() {
            @Override
            public int compare (Integer id1, Integer id2) {
              return Integer.compare(id1, id2);
            }
          };

        return switchToWindow(comparator);
      }
    };

  private final static FunctionKeyAction[] functionKeyActions =
    new FunctionKeyAction[] {
      /* F1  */ functionKeyAction_homeScreen,
      /* F2  */ functionKeyAction_backButton,
      /* F3  */ functionKeyAction_notificationsShade,
      /* F4  */ functionKeyAction_recentApplications,
      /* F5  */ functionKeyAction_serviceSettings,
      /* F6  */ functionKeyAction_quickSettings,
      /* F7  */ functionKeyAction_moveBackward,
      /* F8  */ functionKeyAction_moveForward,
      /* F9  */ functionKeyAction_powerDialog,
      /* F10 */ functionKeyAction_optionsMenu,
      /* F11 */ null,
      /* F12 */ null,
      /* F13 */ null,
      /* F14 */ functionKeyAction_previousWindow,
      /* F15 */ functionKeyAction_nextWindow,
      /* F16 */ functionKeyAction_logScreen
    };

  public static boolean inputKey_function (int key) {
    if (key < 0) return false;
    if (key >= functionKeyActions.length) return false;

    FunctionKeyAction action = functionKeyActions[key];
    if (action == null) return false;

    return action.performAction();
  }
}
