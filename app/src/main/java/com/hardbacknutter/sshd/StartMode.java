package com.hardbacknutter.sshd;

public enum StartMode {
    /** User started, or started when the app starts. */
    ByUser,
    /** An external (to this app) start request. */
    ByIntent,
    /** We're starting the service when the device is booting. */
    OnBoot
}
