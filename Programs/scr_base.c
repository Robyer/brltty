/*
 * BRLTTY - A background process providing access to the console screen (when in
 *          text mode) for a blind person using a refreshable braille display.
 *
 * Copyright (C) 1995-2014 by The BRLTTY Developers.
 *
 * BRLTTY comes with ABSOLUTELY NO WARRANTY.
 *
 * This is free software, placed under the terms of the
 * GNU General Public License, as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any
 * later version. Please see the file LICENSE-GPL for details.
 *
 * Web Page: http://mielke.cc/brltty/
 *
 * This software is maintained by Dave Mielke <dave@mielke.cc>.
 */

#include "prologue.h"

#include <stdio.h>
#include <string.h>

#include "log.h"
#include "scr.h"
#include "scr_base.h"

int
isSpecialKey (ScreenKey key) {
  return (key & (SCR_KEY_CHAR_MASK & ~0XFF)) == SCR_KEY_UNICODE_ROW;
}

void
setKeyModifiers (ScreenKey *key, ScreenKey which) {
  if (!isSpecialKey(*key)) {
    wchar_t character = *key & SCR_KEY_CHAR_MASK;
    ScreenKey modifiers = *key & ~SCR_KEY_CHAR_MASK;

    if (which & (SCR_KEY_UPPER | SCR_KEY_SHIFT)) {
      if (!(modifiers & (SCR_KEY_UPPER | SCR_KEY_SHIFT))) {
        if (iswupper(character)) {
          character = towlower(character);

          if (which & SCR_KEY_UPPER) {
            modifiers |= SCR_KEY_UPPER;
          } else {
            modifiers |= SCR_KEY_SHIFT;
          }
        }
      }
    } else {
      if (modifiers & (SCR_KEY_UPPER | SCR_KEY_SHIFT)) {
        if (iswalpha(character)) {
          character = towupper(character);
          modifiers &= ~SCR_KEY_SHIFT;
        }

        modifiers &= ~SCR_KEY_UPPER;
      }
    }

    if (which & SCR_KEY_CONTROL) {
      if (!(modifiers & SCR_KEY_CONTROL)) {
        if (character < 0X20) {
          character |= 0X60;
          modifiers |= SCR_KEY_CONTROL;
        }
      }
    } else {
      if (modifiers & SCR_KEY_CONTROL) {
        if (iswLatin1(character)) {
          if ((character & 0X6F) == 0X2F) {
            character |= 0X50;
          } else {
            character &= 0X9F;
          }
        }
        modifiers &= ~SCR_KEY_CONTROL;
      }
    }

    *key = character | modifiers;
  }
}

static const char text_BaseScreen[] = " ";

static int
selectVirtualTerminal_BaseScreen (int vt) {
  return 0;
}

static int
switchVirtualTerminal_BaseScreen (int vt) {
  return 0;
}

static int
currentVirtualTerminal_BaseScreen (void) {
  return -1;
}

static size_t
formatTitle_BaseScreen (char *buffer, size_t size) {
  *buffer = 0;
  return 0;
}

static int
poll_BaseScreen (void) {
  return 0;
}

static int
refresh_BaseScreen (void) {
  return 1;
}

static void
describe_BaseScreen (ScreenDescription *description) {
  description->rows = 1;
  description->cols = strlen(text_BaseScreen);
  description->posx = 0;
  description->posy = 0;
  description->number = currentVirtualTerminal_BaseScreen();
}

static int
readCharacters_BaseScreen (const ScreenBox *box, ScreenCharacter *buffer) {
  ScreenDescription description;
  describe_BaseScreen(&description);
  if (!validateScreenBox(box, description.cols, description.rows)) return 0;
  setScreenMessage(box, buffer, text_BaseScreen);
  return 1;
}

static int
insertKey_BaseScreen (ScreenKey key) {
  return 0;
}

static int
routeCursor_BaseScreen (int column, int row, int screen) {
  return 0;
}

static int
highlightRegion_BaseScreen (int left, int right, int top, int bottom) {
  return 0;
}

int
unhighlightRegion_BaseScreen (void) {
  return 0;
}

static int
getPointer_BaseScreen (int *column, int *row) {
  return 0;
}

static int
handleCommand_BaseScreen (int command) {
  return 0;
}

static KeyTableCommandContext
getCommandContext_BaseScreen (void) {
  return KTB_CTX_DEFAULT;
}

void
initializeBaseScreen (BaseScreen *base) {
  base->formatTitle = formatTitle_BaseScreen;

  base->poll = poll_BaseScreen;
  base->refresh = refresh_BaseScreen;

  base->describe = describe_BaseScreen;
  base->readCharacters = readCharacters_BaseScreen;
  base->insertKey = insertKey_BaseScreen;

  base->routeCursor = routeCursor_BaseScreen;
  base->highlightRegion = highlightRegion_BaseScreen;
  base->unhighlightRegion = unhighlightRegion_BaseScreen;
  base->getPointer = getPointer_BaseScreen;

  base->selectVirtualTerminal = selectVirtualTerminal_BaseScreen;
  base->switchVirtualTerminal = switchVirtualTerminal_BaseScreen;
  base->currentVirtualTerminal = currentVirtualTerminal_BaseScreen;

  base->handleCommand = handleCommand_BaseScreen;
  base->getCommandContext = getCommandContext_BaseScreen;
}

void
describeBaseScreen (BaseScreen *base, ScreenDescription *description) {
  description->cols = description->rows = 1;
  description->posx = description->posy = 0;
  description->number = 0;
  description->cursor = 1;
  description->unreadable = NULL;
  base->describe(description);

  if (description->unreadable) {
    description->cursor = 0;
  } else if (description->number == -1) {
    description->unreadable = "unreadable screen";
  }
}

int
validateScreenBox (const ScreenBox *box, int columns, int rows) {
  if ((box->left >= 0))
    if ((box->width > 0))
      if (((box->left + box->width) <= columns))
        if ((box->top >= 0))
          if ((box->height > 0))
            if (((box->top + box->height) <= rows))
              return 1;

  logMessage(LOG_ERR, "invalid screen area: cols=%d left=%d width=%d rows=%d top=%d height=%d",
             columns, box->left, box->width,
             rows, box->top, box->height);
  return 0;
}

void
setScreenMessage (const ScreenBox *box, ScreenCharacter *buffer, const char *message) {
  const ScreenCharacter *end = buffer + box->width;
  unsigned int index = 0;
  size_t length = strlen(message);
  mbstate_t state;

  memset(&state, 0, sizeof(state));
  clearScreenCharacters(buffer, (box->width * box->height));

  while (length) {
    wchar_t wc;
    size_t result = mbrtowc(&wc, message, length, &state);
    if ((ssize_t)result < 1) break;

    message += result;
    length -= result;

    if (index++ >= box->left) {
      if (buffer == end) break;
      (buffer++)->text = wc;
    }
  }
}