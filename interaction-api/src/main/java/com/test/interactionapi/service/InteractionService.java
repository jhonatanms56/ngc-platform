package com.test.interactionapi.service;

import com.newrelic.api.agent.NewRelic;
import com.test.interactionapi.domain.Interaction;
import com.test.interactionapi.dto.CreateInteractionRequest;
import com.test.interactionapi.dto.InteractionMetrics;
import com.test.interactionapi.exception.InteractionNotFoundException;
import com.test.interactionapi.repository.InteractionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InteractionService {

    private final InteractionRepository repository;

    @Autowired(required = false)
    @Nullable
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topic.interactions}")
    private String interactionsTopic;

    public Interaction create(CreateInteractionRequest request) {
        Interaction interaction = Interaction.builder()
                .id(UUID.randomUUID().toString())
                .customerId(request.customerId())
                .channel(request.channel())
                .agentId(request.agentId())
                .queueWaitMs(request.queueWaitMs())
                .status(Interaction.Status.CREATED)
                .createdAt(Instant.now())
                .build();

        Interaction saved = repository.save(interaction);

        Map<String, Object> nrAttributes = new HashMap<>();
        nrAttributes.put("customerId", saved.getCustomerId());
        nrAttributes.put("channel", saved.getChannel().toString());
        nrAttributes.put("agentId", saved.getAgentId());
        nrAttributes.put("queueWaitMs", saved.getQueueWaitMs());
        NewRelic.getAgent().getInsights().recordCustomEvent("InteractionCreated", nrAttributes);

        if (kafkaTemplate != null) {
            kafkaTemplate.send(interactionsTopic, saved.getId(), Map.of(
                    "id", saved.getId(),
                    "customerId", saved.getCustomerId(),
                    "channel", saved.getChannel().toString(),
                    "status", saved.getStatus().toString(),
                    "createdAt", saved.getCreatedAt().toString()
            ));
        }

        return saved;
    }

    public Interaction findById(String id) {
        Interaction interaction = repository.findById(id)
                .orElseThrow(() -> new InteractionNotFoundException(id));

        Map<String, Object> nrAttributes = new HashMap<>();
        nrAttributes.put("interactionId", interaction.getId());
        nrAttributes.put("channel", interaction.getChannel().toString());
        NewRelic.getAgent().getInsights().recordCustomEvent("InteractionFetched", nrAttributes);

        return interaction;
    }

    public InteractionMetrics getMetrics() {
        long totalInteractions = repository.count();

        List<Object[]> channelRows = repository.countByChannel();
        Map<String, Long> byChannel = new HashMap<>();
        for (Object[] row : channelRows) {
            byChannel.put(row[0].toString(), (Long) row[1]);
        }

        Double avgRaw = repository.avgQueueWaitMs();
        double avgQueueWaitMs = avgRaw != null ? avgRaw : 0.0;

        long resolvedCount = repository.countByStatus(Interaction.Status.RESOLVED);

        double resolutionRate = totalInteractions > 0
                ? resolvedCount / (double) totalInteractions
                : 0.0;

        return new InteractionMetrics(totalInteractions, byChannel, avgQueueWaitMs, resolvedCount, resolutionRate);
    }
}
