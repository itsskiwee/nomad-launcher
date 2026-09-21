package com.rawal.pocketdeck;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the system lets Pocket Deck read active media sessions
 * (MediaSessionManager requires an enabled notification listener component).
 * Notifications themselves are never read.
 */
public class MediaListener extends NotificationListenerService {
}
