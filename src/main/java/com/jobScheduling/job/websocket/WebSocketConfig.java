package com.jobScheduling.job.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket configuration using STOMP over SockJS.
 *
 * Enables the dashboard to receive real-time execution events without polling.
 *
 * Topic layout:
 *   /topic/executions        — all execution events (broadcast)
 *   /topic/jobs/{id}         — events for a specific job
 *   /topic/metrics           — live system metrics (req/s, kafka lag, etc.)
 *
 * Resume talking point: "Implemented real-time event streaming to a developer
 * dashboard using WebSocket (STOMP/SockJS), eliminating the need for polling
 * and reducing dashboard latency from seconds to milliseconds."
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable in-memory broker for /topic prefix
        config.enableSimpleBroker("/topic");
        // Prefix for messages FROM client to server (not used in this project)
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")  // tighten in production
                .withSockJS();                   // fallback for browsers without WS
    }
}
