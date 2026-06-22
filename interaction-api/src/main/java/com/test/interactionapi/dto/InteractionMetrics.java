package com.test.interactionapi.dto;

import java.util.Map;

public record InteractionMetrics(
        long totalInteractions,
        Map<String, Long> byChannel,
        double avgQueueWaitMs,
        long resolvedCount,
        double resolutionRate
) {}
