package com.fooddelivery.delivery.entity;

import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Point;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "delivery_executives")
@Data
@NoArgsConstructor
public class DeliveryExecutive {

    @Id
    private UUID id;

    private String name;
    private String phoneNumber;
    private String vehicleNumber;

    @Enumerated(EnumType.STRING)
    private DeliveryExecutiveStatus status;

    @JsonIgnore
    private Point lastKnownLocation;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
