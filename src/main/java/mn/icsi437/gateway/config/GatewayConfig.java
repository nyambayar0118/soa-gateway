package mn.icsi437.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.*;

import java.util.List;

@Configuration
public class GatewayConfig {

    @Value("${services.auth}")        private String authUrl;
    @Value("${services.profile}")     private String profileUrl;
    @Value("${services.filemanager}") private String fileManagerUrl;

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public RouterFunction<ServerResponse> routes(WebClient.Builder builder) {
        WebClient authClient        = builder.baseUrl(authUrl).build();
        WebClient profileClient     = builder.baseUrl(profileUrl).build();
        WebClient fileManagerClient = builder.baseUrl(fileManagerUrl).build();

        return RouterFunctions.route()
            .path("/auth",  () -> proxy(authClient))
            .path("/users", () -> proxy(profileClient))
            .path("/files", () -> proxy(fileManagerClient))
            .path("/admin", () -> proxy(profileClient))
            .build();
    }

    @Bean
    public CorsWebFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:8084", "http://localhost:8082"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }

    private RouterFunction<ServerResponse> proxy(WebClient client) {
        return RouterFunctions.route()
            .route(RequestPredicates.all(), request ->
                client.method(request.method())
                    .uri(request.uri().getPath() +
                        (request.uri().getQuery() != null
                            ? "?" + request.uri().getQuery() : ""))
                    .headers(h -> h.addAll(request.headers().asHttpHeaders()))
                    .body(request.bodyToMono(byte[].class), byte[].class)
                    .exchangeToMono(clientResponse ->
                        clientResponse.bodyToMono(byte[].class)
                            .defaultIfEmpty(new byte[0])
                            .flatMap(body ->
                                ServerResponse
                                    .status(clientResponse.statusCode())
                                    .headers(h -> {
                                        clientResponse.headers().asHttpHeaders()
                                            .forEach((key, values) -> {
                                                if (!key.equalsIgnoreCase("transfer-encoding")) {
                                                    h.addAll(key, values);
                                                }
                                            });
                                    })
                                    .bodyValue(body)
                            )
                    )
            )
            .build();
    }
}