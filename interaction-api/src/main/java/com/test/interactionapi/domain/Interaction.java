package com.test.interactionapi.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "interactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Interaction {

    @Id
    private String id;

    @Column(nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    private String agentId;

    private Long queueWaitMs;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Status status = Status.CREATED;

    private Instant createdAt;

    public enum Channel { VOICE, CHAT, EMAIL, SMS }

    public enum Status { CREATED, IN_PROGRESS, RESOLVED }
}
