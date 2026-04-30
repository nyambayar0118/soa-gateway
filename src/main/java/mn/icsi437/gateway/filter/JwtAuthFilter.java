package mn.icsi437.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@Order(-1)
public class JwtAuthFilter implements WebFilter {

    private static final List<String> PUBLIC_PATHS = List.of(
            "/auth/login",
            "/auth/register"
    );

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired private RoleGuard roleGuard;
    @Autowired private RequestMutator requestMutator;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().toString();
        String method = exchange.getRequest().getMethod().toString();
        
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return chain.filter(exchange);
        }

        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return respond(exchange, HttpStatus.UNAUTHORIZED, "Missing or malformed Authorization header");
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .parseSignedClaims(authHeader.substring(7))
                    .getPayload();
        } catch (JwtException e) {
            return respond(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }

        if (!roleGuard.isPermitted(exchange, claims)) {
            return respond(exchange, HttpStatus.FORBIDDEN, "Insufficient role for this route");
        }

        return chain.filter(requestMutator.injectHeaders(exchange, claims));
    }

    private Mono<Void> respond(ServerWebExchange exchange, HttpStatus status, String reason) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().add("X-Gateway-Reason", reason);
        return response.setComplete();
    }
}