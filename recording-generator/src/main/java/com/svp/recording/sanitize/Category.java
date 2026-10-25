package com.svp.recording.sanitize;

/** Regulatory bucket of a sensitive field. */
public enum Category {
    /** PCI-DSS cardholder data and sensitive authentication data. */
    PCI,
    /** Personally identifiable information. */
    PII,
    /** Credentials: passwords, tokens, keys. */
    SECRET
}
