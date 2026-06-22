package com.test.interactionapi.service;

import com.newrelic.api.agent.Agent;
import com.newrelic.api.agent.Insights;
import com.newrelic.api.agent.NewRelic;
import com.test.interactionapi.domain.Interaction;
import com.test.interactionapi.dto.CreateInteractionRequest;
import com.test.interactionapi.dto.InteractionMetrics;
import com.test.interactionapi.exception.InteractionNotFoundException;
import com.test.interactionapi.repository.InteractionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InteractionServiceTest {

    @Mock
    InteractionRepository repository;

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    InteractionService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "interactionsTopic", "interaction-events");
    }

    @Test
    void create_savesInteractionAndPublishesKafkaMessage() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<NewRelic> nrMock = mockStatic(NewRelic.class)) {
            Agent mockAgent = mock(Agent.class);
            Insights mockInsights = mock(Insights.class);
            nrMock.when(NewRelic::getAgent).thenReturn(mockAgent);
            when(mockAgent.getInsights()).thenReturn(mockInsights);

            CreateInteractionRequest request = new CreateInteractionRequest(
                    "cust-001", Interaction.Channel.VOICE, null, 1200L);

            Interaction result = service.create(request);

            verify(repository).save(any(Interaction.class));
            verify(kafkaTemplate).send(eq("interaction-events"), anyString(), any());
            assertThat(result.getCustomerId()).isEqualTo("cust-001");
            assertThat(result.getStatus()).isEqualTo(Interaction.Status.CREATED);
        }
    }

    @Test
    void create_recordsNewRelicCustomEvent() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<NewRelic> nrMock = mockStatic(NewRelic.class)) {
            Agent mockAgent = mock(Agent.class);
            Insights mockInsights = mock(Insights.class);
            nrMock.when(NewRelic::getAgent).thenReturn(mockAgent);
            when(mockAgent.getInsights()).thenReturn(mockInsights);

            CreateInteractionRequest request = new CreateInteractionRequest(
                    "cust-001", Interaction.Channel.VOICE, "agent-42", 800L);

            service.create(request);

            verify(mockInsights).recordCustomEvent(eq("InteractionCreated"), argThat(attrs ->
                    "cust-001".equals(attrs.get("customerId")) &&
                    "VOICE".equals(attrs.get("channel"))
            ));
        }
    }

    @Test
    void findById_throwsInteractionNotFoundException_whenNotFound() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById("missing"))
                .isInstanceOf(InteractionNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void getMetrics_returnsCorrectResolutionRate() {
        when(repository.count()).thenReturn(4L);
        when(repository.countByStatus(Interaction.Status.RESOLVED)).thenReturn(2L);
        when(repository.avgQueueWaitMs()).thenReturn(750.0);
        when(repository.countByChannel()).thenReturn(List.of(
                new Object[]{Interaction.Channel.VOICE, 3L},
                new Object[]{Interaction.Channel.CHAT, 1L}
        ));

        InteractionMetrics metrics = service.getMetrics();

        assertThat(metrics.totalInteractions()).isEqualTo(4);
        assertThat(metrics.resolvedCount()).isEqualTo(2);
        assertThat(metrics.resolutionRate()).isEqualTo(0.5);
        assertThat(metrics.avgQueueWaitMs()).isEqualTo(750.0);
        assertThat(metrics.byChannel()).containsEntry("VOICE", 3L);
    }
}
