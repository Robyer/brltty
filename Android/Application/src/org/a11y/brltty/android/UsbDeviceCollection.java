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

import java.util.Collection;
import java.util.Map;

import android.os.Build;
import android.content.Context;
import android.hardware.usb.*;

public final class UsbDeviceCollection extends DeviceCollection {
  public static final String DEVICE_QUALIFIER = "usb";

  @Override
  public String getQualifier () {
    return DEVICE_QUALIFIER;
  }

  private final UsbManager manager;
  private final Map<String, UsbDevice> map;
  private final Collection<UsbDevice> devices;

  public UsbDeviceCollection (Context context) {
    super();
    manager = (UsbManager)context.getSystemService(Context.USB_SERVICE);
    map = manager.getDeviceList();
    devices = map.values();
  }

  private final String getManufacturerName (UsbDevice device) {
    if (ApplicationUtilities.haveSdkVersion(Build.VERSION_CODES.LOLLIPOP)) {
      return device.getManufacturerName();
    } else {
      return null;
    }
  }

  private final String getProductName (UsbDevice device) {
    if (ApplicationUtilities.haveSdkVersion(Build.VERSION_CODES.LOLLIPOP)) {
      return device.getProductName();
    } else {
      return null;
    }
  }

  private final String getSerialNumber (UsbDevice device) {
    if (ApplicationUtilities.haveSdkVersion(Build.VERSION_CODES.LOLLIPOP)) {
      return device.getSerialNumber();
    } else {
      UsbDeviceConnection connection = manager.openDevice(device);
      if (connection == null) return null;

      try {
        return connection.getSerial();
      } finally {
        connection.close();
      }
    }
  }

  @Override
  public String[] getIdentifiers () {
    StringMaker<UsbDevice> stringMaker = new StringMaker<UsbDevice>() {
      @Override
      public String makeString (UsbDevice device) {
        return device.getDeviceName();
      }
    };

    return makeStringArray(devices, stringMaker);
  }

  @Override
  public String[] getLabels () {
    StringMaker<UsbDevice> stringMaker = new StringMaker<UsbDevice>() {
      @Override
      public String makeString (UsbDevice device) {
        StringBuilder label = new StringBuilder();

        label.append(
          String.format(
            "[%04X:%04X]",
            device.getVendorId(),
            device.getProductId()
          )
        );

        boolean first = true;
        String[] components = new String[] {
          getManufacturerName(device),
          getProductName(device),
          getSerialNumber(device)
        };

        for (String component : components) {
          if (component == null) continue;
          if (component.isEmpty()) continue;

          if (first) {
            first = false;
          } else {
            label.append(',');
          }

          label.append(' ');
          label.append(component);
        }

        return label.toString();
      }
    };

    return makeStringArray(devices, stringMaker);
  }

  @Override
  public String makeReference (String identifier) {
    UsbDevice device = map.get(identifier);
    String serialNumber = getSerialNumber(device);

    String reference = String.format(
      "vendorIdentifier=0X%04X+productIdentifier=0X%04X",
      device.getVendorId(), device.getProductId()
    );

    if ((serialNumber != null) && !serialNumber.isEmpty()) {
      reference += "+serialNumber=" + serialNumber;
    }

    return reference;
  }
}
