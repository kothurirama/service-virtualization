package com.svp.recording.sanitize;

/** What a detector believes a field contains. */
public enum SensitiveType {
    PAN(Category.PCI),
    CVV(Category.PCI),
    PIN(Category.PCI),
    TRACK_DATA(Category.PCI),
    CARD_EXPIRY(Category.PCI),

    SSN(Category.PII),
    NATIONAL_ID(Category.PII),
    EMAIL(Category.PII),
    PHONE(Category.PII),
    PERSON_NAME(Category.PII),
    DATE_OF_BIRTH(Category.PII),
    ADDRESS(Category.PII),
    BANK_ACCOUNT(Category.PII),

    PASSWORD(Category.SECRET),
    API_KEY(Category.SECRET),
    ACCESS_TOKEN(Category.SECRET),
    JWT(Category.SECRET),
    PRIVATE_KEY(Category.SECRET),
    AUTH_HEADER(Category.SECRET),
    HIGH_ENTROPY_SECRET(Category.SECRET);

    private final Category category;

    SensitiveType(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
