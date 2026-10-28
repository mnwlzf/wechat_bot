package com.example.wechatbot.security;

import com.example.wechatbot.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ApiAuthFilter.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public ApiAuthFilter(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void warnAboutDefaults() {
        if (properties.getSecurity().getAdminToken().startsWith("change-me")
                || properties.getSecurity().getAgentToken().startsWith("change-me")
                || properties.getSecurity().getSecretKey().startsWith("dev-only")) {
            log.warn("Security tokens or encryption key are using development defaults. Change them before deployment.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean authorized;
        if (path.startsWith("/api/agent/")) {
            authorized = constantEquals(properties.getSecurity().getAgentToken(), request.getHeader("X-Agent-Token"));
        } else if (path.startsWith("/api/admin/")) {
            String bearer = request.getHeader("Authorization");
            String token = bearer != null && bearer.startsWith("Bearer ") ? bearer.substring(7).trim() : request.getHeader("X-Admin-Token");
            authorized = constantEquals(properties.getSecurity().getAdminToken(), token);
        } else {
            authorized = true;
        }

        if (!authorized) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "code", 401,
                    "message", "认证失败，请检查 Admin/Agent Token",
                    "timestamp", OffsetDateTime.now().toString()
            ));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean constantEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
