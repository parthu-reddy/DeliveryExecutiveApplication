package com.fooddelivery.delivery.service.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class CandidateFoundStrategyTest {

    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private CandidateFoundStrategy strategy;

    @Test
    void testGetEventTypes() {
        assertThat(strategy.getEventTypes()).containsExactly("DISPATCH_CANDIDATE_FOUND");
    }
}
