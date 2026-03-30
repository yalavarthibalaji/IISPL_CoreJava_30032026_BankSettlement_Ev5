package com.iispl.banksettlement.entity;

import java.time.LocalDateTime;

/**
 * Auditable interface — any class implementing this promises to expose
 * its audit timestamps and the user who created the record.
 *
 * All persistent domain classes implement this via BaseEntity.
 */
public interface Auditable {

    LocalDateTime getCreatedAt();

    LocalDateTime getUpdatedAt();

    String getCreatedBy();
}
