package com.payflash.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket at /ws. Clients subscribe to /topic/sales/{id} for live stock counts.
 * The simple in-memory broker is enough because each instance reads stock from the shared Redis and
 * pushes it to its own subscribers; no cross-instance relay is needed.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final String[] allowedOrigins;

    public WebSocketConfig(@Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:3000}")
                           String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
    }

    /**
     * The socket is read-only for clients: they may only subscribe to a sale's stock topic. Without this,
     * anyone could SEND a fake "0 left" message to /topic/sales/42 and the broker would show it to every viewer.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor stomp = StompHeaderAccessor.wrap(message);
                StompCommand command = stomp.getCommand();
                if (command == null) {
                    return message; // heartbeats
                }
                switch (command) {
                    case CONNECT, STOMP, DISCONNECT, UNSUBSCRIBE -> {
                        return message;
                    }
                    case SUBSCRIBE -> {
                        String destination = stomp.getDestination();
                        if (destination != null && destination.matches("/topic/sales/[0-9]{1,18}")) {
                            return message;
                        }
                        throw new MessageDeliveryException("Subscriptions are limited to /topic/sales/{id}");
                    }
                    default -> throw new MessageDeliveryException("Clients may not send messages");
                }
            }
        });
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
