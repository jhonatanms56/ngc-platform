package com.test.interactionapi.controller;

import com.test.interactionapi.domain.Interaction;
import com.test.interactionapi.dto.CreateInteractionRequest;
import com.test.interactionapi.dto.InteractionMetrics;
import com.test.interactionapi.service.InteractionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/interactions")
@RequiredArgsConstructor
public class InteractionController {

    private final InteractionService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Interaction create(@RequestBody @Valid CreateInteractionRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    public Interaction findById(@PathVariable String id) {
        return service.findById(id);
    }

    @GetMapping("/metrics")
    public InteractionMetrics getMetrics() {
        return service.getMetrics();
    }
}
