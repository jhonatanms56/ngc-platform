package com.test.interactionapi.controller;

import com.test.interactionapi.domain.Interaction;
import com.test.interactionapi.dto.InteractionMetrics;
import com.test.interactionapi.exception.GlobalExceptionHandler;
import com.test.interactionapi.exception.InteractionNotFoundException;
import com.test.interactionapi.service.InteractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({InteractionController.class, GlobalExceptionHandler.class})
class InteractionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    InteractionService service;

    @Test
    void postValidInteraction_returns201WithId() throws Exception {
        Interaction saved = Interaction.builder()
                .id("abc-123")
                .customerId("cust-001")
                .channel(Interaction.Channel.VOICE)
                .status(Interaction.Status.CREATED)
                .createdAt(Instant.now())
                .build();
        when(service.create(any())).thenReturn(saved);

        mockMvc.perform(post("/v1/interactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"cust-001","channel":"VOICE","queueWaitMs":1200}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("abc-123"));
    }

    @Test
    void postMissingCustomerId_returns400() throws Exception {
        mockMvc.perform(post("/v1/interactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"VOICE"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postMissingChannel_returns400() throws Exception {
        mockMvc.perform(post("/v1/interactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"cust-001"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getById_returns200() throws Exception {
        Interaction interaction = Interaction.builder()
                .id("abc-123")
                .customerId("cust-001")
                .channel(Interaction.Channel.CHAT)
                .status(Interaction.Status.CREATED)
                .createdAt(Instant.now())
                .build();
        when(service.findById("abc-123")).thenReturn(interaction);

        mockMvc.perform(get("/v1/interactions/abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("abc-123"));
    }

    @Test
    void getById_unknownId_returns404WithErrorCode() throws Exception {
        when(service.findById("unknown")).thenThrow(new InteractionNotFoundException("unknown"));

        mockMvc.perform(get("/v1/interactions/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("INTERACTION_NOT_FOUND"));
    }

    @Test
    void getMetrics_returns200WithTotalInteractions() throws Exception {
        when(service.getMetrics()).thenReturn(
                new InteractionMetrics(10, Map.of("VOICE", 7L, "CHAT", 3L), 450.0, 6, 0.6));

        mockMvc.perform(get("/v1/interactions/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalInteractions").value(10));
    }
}
