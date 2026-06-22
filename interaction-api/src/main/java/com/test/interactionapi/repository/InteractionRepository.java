package com.test.interactionapi.repository;

import com.test.interactionapi.domain.Interaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface InteractionRepository extends JpaRepository<Interaction, String> {

    long countByStatus(Interaction.Status status);

    @Query("SELECT AVG(i.queueWaitMs) FROM Interaction i")
    Double avgQueueWaitMs();

    @Query("SELECT i.channel, COUNT(i) FROM Interaction i GROUP BY i.channel")
    List<Object[]> countByChannel();
}
