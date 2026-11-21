package com.payflash.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.payflash.common.BusinessException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Calls the Razorpay Orders API (https://razorpay.com/docs/api/orders/create/). */
@Component
public class RazorpayGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);

    private final RazorpayProperties props;
    private final RestClient http;

    public RazorpayGateway(RazorpayProperties props, RestClient.Builder builder) {
        this.props = props;
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.http = builder.baseUrl(props.baseUrl()).requestFactory(requestFactory).build();
    }

    @Override
    public GatewayOrder createOrder(long amountMinor, String currency, String receipt) {
        if (!props.apiConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_NOT_CONFIGURED",
                    "Razorpay keys are not configured on the server");
        }
        try {
            OrderResponse order = http.post()
                    .uri("/v1/orders")
                    .headers(h -> h.setBasicAuth(props.keyId(), props.keySecret()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", amountMinor, "currency", currency, "receipt", receipt))
                    .retrieve()
                    .body(OrderResponse.class);
            if (order == null || order.id() == null) {
                throw new RestClientException("Empty order response");
            }
            return new GatewayOrder(order.id(), order.amount(), order.currency(), order.status());
        } catch (RestClientResponseException e) {
            // Log the provider's message, but never echo it (or our credentials) to the API client.
            log.warn("Razorpay order creation failed: HTTP {} {}", e.getStatusCode().value(),
                    e.getResponseBodyAsString());
            throw gatewayError();
        } catch (RestClientException e) {
            log.warn("Razorpay unreachable: {}", e.toString());
            throw gatewayError();
        }
    }

    private static BusinessException gatewayError() {
        return new BusinessException(HttpStatus.BAD_GATEWAY, "PAYMENT_GATEWAY_ERROR",
                "Payment provider is unavailable, please retry");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderResponse(String id, long amount, String currency, String status) {
    }
}
