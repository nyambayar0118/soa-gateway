package mn.icsi437.gateway.filter;

import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
@Order(0)  // After JwtAuthFilter (-1)
public class CacheFilter implements WebFilter {

    private static final Duration TTL = Duration.ofSeconds(60);

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Only cache GETs
        if (!"GET".equalsIgnoreCase(exchange.getRequest().getMethod().toString())) {
            return chain.filter(exchange);
        }

        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        String path   = exchange.getRequest().getURI().getPath();
        String query  = exchange.getRequest().getURI().getQuery();
        String key    = "cache:" + (userId != null ? userId : "anon")
                      + ":" + path
                      + (query != null ? "?" + query : "");

        return redis.opsForValue().get(key)
            .flatMap(cached -> serveFromCache(exchange, key, cached))
            .switchIfEmpty(Mono.defer(() -> serveFromBackendAndCache(exchange, chain, key)));
    }

    private Mono<Void> serveFromCache(ServerWebExchange exchange, String key, String cached) {
        System.out.println("[CACHE HIT] " + key);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().add("X-Cache", "HIT");
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] bytes = cached.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private Mono<Void> serveFromBackendAndCache(ServerWebExchange exchange, WebFilterChain chain, String key) {
        System.out.println("[CACHE MISS] " + key);
        ServerHttpResponse originalResponse = exchange.getResponse();
        originalResponse.getHeaders().add("X-Cache", "MISS");

        // Decorate the response so we can intercept the body as it streams to the client
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                // Only cache 2xx responses with JSON bodies
                HttpStatus status = HttpStatus.resolve(
                    getStatusCode() != null ? getStatusCode().value() : 0);
                MediaType contentType = getHeaders().getContentType();
                boolean cacheable = status != null
                    && status.is2xxSuccessful()
                    && contentType != null
                    && contentType.includes(MediaType.APPLICATION_JSON);

                if (!cacheable) {
                    return super.writeWith(body);
                }

                Flux<DataBuffer> bodyFlux = Flux.from(body);
                return super.writeWith(
                    DataBufferUtils.join(bodyFlux).flatMap(joined -> {
                        // Read bytes from buffer (without consuming so we can still send to client)
                        byte[] bytes = new byte[joined.readableByteCount()];
                        joined.read(bytes);
                        DataBufferUtils.release(joined);

                        String json = new String(bytes, StandardCharsets.UTF_8);

                        // Fire-and-forget cache write — don't block client response on Redis
                        redis.opsForValue()
                            .set(key, json, TTL)
                            .doOnError(e -> System.err.println("[CACHE] Redis write failed: " + e.getMessage()))
                            .subscribe();

                        // Send original bytes to client
                        DataBuffer rebuilt = bufferFactory().wrap(bytes);
                        return Mono.just(rebuilt);
                    })
                );
            }
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }
}