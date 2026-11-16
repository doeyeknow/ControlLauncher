package app.control.launcher;

import android.app.admin.DeviceAdminReceiver;

/**
 * Device-admin receiver (v1.1). The policies requested are only
 * force-lock and wipe-data (see res/xml/device_admin.xml). Activation
 * state changes are not audit-logged (per design); the lock/wipe
 * attempts and outcomes are logged in AdminControl, where they happen.
 */
public class AdminReceiver extends DeviceAdminReceiver {
}
