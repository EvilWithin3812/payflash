package com.payflash.sale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "sales")
public class Sale {

    public enum Phase { UPCOMING, LIVE, ENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(name = "total_units", nullable = false)
    private int totalUnits;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "hold_seconds", nullable = false)
    private int holdSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Sale() {
    }

    public Sale(String name, String description, BigDecimal price, int totalUnits, Instant startsAt, Instant endsAt,
                int holdSeconds) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.totalUnits = totalUnits;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.holdSeconds = holdSeconds;
    }

    public Phase phaseAt(Instant now) {
        if (now.isBefore(startsAt)) {
            return Phase.UPCOMING;
        }
        return now.isBefore(endsAt) ? Phase.LIVE : Phase.ENDED;
    }

    public Duration holdDuration() {
        return Duration.ofSeconds(holdSeconds);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getTotalUnits() {
        return totalUnits;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public int getHoldSeconds() {
        return holdSeconds;
    }
}
