package com.fooddelivery.delivery.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The session registry must be emptied when a socket closes.
 *
 * <p>Written after the {@code ConcurrentWebSocketSessionDecorator} fix: sessions are wrapped before
 * being stored, but {@code afterConnectionClosed} is handed the RAW session by the container. The
 * decorator does not override {@code equals}, so the previous
 * {@code userSessions.remove(userId, session)} compared a decorator against a raw session, never
 * matched, and leaked the entry on every disconnect. Matching on session id fixes it.
 *
 * <p>Both tests matter: the first would pass again if someone "fixed" it with an unconditional
 * {@code remove(userId)}, and the second is what makes that wrong.
 */
class SessionRegistryLifecycleTest {

    private final LocationTrackingWebSocketHandler handler = new LocationTrackingWebSocketHandler(
            new ObjectMapper(), mock(StringRedisTemplate.class), new SimpleMeterRegistry());

    private static final String DRIVER = "4f4a4e37-6ca5-5598-94f1-43ef1628f631";

    private WebSocketSession sessionWithId(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", DRIVER);
        when(s.getId()).thenReturn(id);
        when(s.getAttributes()).thenReturn(attrs);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void closingASocketRemovesItFromTheRegistry() throws Exception {
        WebSocketSession session = sessionWithId("session-1");

        handler.afterConnectionEstablished(session);
        assertThat(handler.sessionCountForTest()).isEqualTo(1);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(handler.sessionCountForTest())
                .describedAs("a closed socket must not linger in userSessions")
                .isZero();
    }

    @Test
    void aReconnectBeforeTheOldCloseArrivesKeepsTheNewSession() throws Exception {
        WebSocketSession oldSession = sessionWithId("session-1");
        WebSocketSession newSession = sessionWithId("session-2");

        handler.afterConnectionEstablished(oldSession);
        handler.afterConnectionEstablished(newSession);

        // The old socket's close callback arrives late, after the driver already reconnected.
        handler.afterConnectionClosed(oldSession, CloseStatus.NORMAL);

        assertThat(handler.sessionCountForTest())
                .describedAs("the live reconnected session must survive the stale close")
                .isEqualTo(1);
    }
}
