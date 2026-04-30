package mn.icsi437.gateway.filter;

import io.jsonwebtoken.Claims;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

@Component
public class RequestMutator {

    public ServerWebExchange injectHeaders(ServerWebExchange exchange, Claims claims) {
        String userId   = claims.getSubject();
        String username = claims.get("username", String.class);

        @SuppressWarnings("unchecked")
        List<String> rolesList = claims.get("roles", List.class);
        String roles = (rolesList != null) ? String.join(",", rolesList) : "";

        ServerHttpRequest mutatedRequest = exchange.getRequest()
                .mutate()
                .header("X-User-Id",    userId   != null ? userId   : "")
                .header("X-Username",   username != null ? username : "")
                .header("X-User-Roles", roles)
                .headers(h -> h.remove("Authorization"))
                .build();

        return exchange.mutate().request(mutatedRequest).build();
    }
}