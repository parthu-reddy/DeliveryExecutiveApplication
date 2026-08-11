package com.fooddelivery.delivery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import java.time.OffsetDateTime;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column(name = "key")
    private String key;

    @CreationTimestamp
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public IdempotencyKey() {}

    public IdempotencyKey(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
