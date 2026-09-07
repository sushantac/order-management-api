package com.company.orderapi.security.pii;

/**
 * PR #27 - classification of the PII kinds the API knows how to mask.
 */
public enum PiiType {
    EMAIL,
    PHONE,
    NAME
}
