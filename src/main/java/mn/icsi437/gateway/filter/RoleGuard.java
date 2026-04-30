package mn.icsi437.gateway.filter;

import io.jsonwebtoken.Claims;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

@Component
public class RoleGuard {

    public boolean isPermitted(ServerWebExchange exchange, Claims claims) {
        String path   = exchange.getRequest().getPath().toString();
        HttpMethod method = exchange.getRequest().getMethod();

        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        if (roles == null) roles = List.of();
        
        System.out.println("[RoleGuard] path=" + path + " method=" + method + " roles=" + roles);

        boolean isAdmin = roles.contains("ROLE_ADMIN");
        boolean isUser  = roles.contains("ROLE_USER") || isAdmin; // admin inherits user

        // --- /users routes ---
        if (path.matches("^/users/\\d+$")) {
            if (HttpMethod.DELETE.equals(method)) return isAdmin;
            if (HttpMethod.GET.equals(method) || HttpMethod.PUT.equals(method)) return isUser;
            return false;
        }

        if (path.equals("/users")) {
            if (HttpMethod.GET.equals(method))  return isAdmin; // list all = admin only
            if (HttpMethod.POST.equals(method)) return isAdmin; // create via admin only
            return false;
        }

        // --- /files routes ---
        if (path.startsWith("/files/")) {
            return isUser;
        }

        // --- /admin routes ---
        if (path.startsWith("/admin/")) {
            return isAdmin;
        }

        // Deny anything else
        return false;
    }
}