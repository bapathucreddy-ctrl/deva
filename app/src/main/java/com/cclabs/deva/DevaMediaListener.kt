package com.cclabs.deva

import android.service.notification.NotificationListenerService

// Android grants access to active media sessions only after the user explicitly
// enables DEVA notification access in system settings.
class DevaMediaListener : NotificationListenerService()
