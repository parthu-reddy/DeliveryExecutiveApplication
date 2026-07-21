package com.fooddelivery.delivery.entity;

import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Point;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Entity
@Table(name = "delivery_executives")
@Data
@NoArgsConstructor
@Slf4j
public class DeliveryExecutive {

    @Id
    private UUID id;

    private String fullName;
    private String phoneNumber;
    private String vehicleNumber;
    private String photoUrl;

    @Enumerated(EnumType.STRING)
    private DeliveryExecutiveStatus status;

    public void setStatus(DeliveryExecutiveStatus status) {
        if (this.status != status) {
            log.info("Delivery executive {} status changing from {} to {}", this.id, this.status, status);
        }
        this.status = status;
    }

    @Version
    private Integer version;

    @JsonIgnore
    private Point lastKnownLocation;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
