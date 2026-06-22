package com.test.interactionapi.dto;

import com.test.interactionapi.domain.Interaction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateInteractionRequest(
        @NotBlank String customerId,
        @NotNull Interaction.Channel channel,
        String agentId,
        Long queueWaitMs
) {}
