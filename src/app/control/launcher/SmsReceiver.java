package app.control.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Receives SMS only to check for authenticated Control commands. Normal
 * texts are ignored by this app and remain in the user's SMS app.
 */
public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) return;
        Map<String, StringBuilder> bySender = new LinkedHashMap<>();
        for (SmsMessage message : messages) {
            if (message == null) continue;
            String sender = message.getOriginatingAddress();
            if (sender == null) continue;
            String body = message.getMessageBody();
            bySender.computeIfAbsent(sender, k -> new StringBuilder())
                    .append(body == null ? "" : body);
        }
        if (bySender.isEmpty()) return;
        PendingResult pending = goAsync();
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                for (Map.Entry<String, StringBuilder> e : bySender.entrySet()) {
                    SmsControl.handle(appContext, e.getKey(), e.getValue().toString());
                }
            } finally {
                pending.finish();
            }
        }, "sms-control").start();
    }
}
