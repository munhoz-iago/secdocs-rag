package dev.secdocs;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_PER_MINUTE = 20;
    private static final int MAX_CLIENTS = 10_000;
    private final ConcurrentHashMap<String, Window> clients = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final Clock clock;

    public ApiRateLimitFilter() {
        this(Clock.systemUTC());
    }

    ApiRateLimitFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!"POST".equals(request.getMethod()) || !("/api/ask".equals(path)
                || "/api/search".equals(path) || "/consultar".equals(path))) {
            chain.doFilter(request, response);
            return;
        }
        long minute = clock.instant().getEpochSecond() / 60;
        if (requests.incrementAndGet() % 1000 == 0) {
            clients.entrySet().removeIf(entry -> entry.getValue().minute < minute);
        }
        String client = request.getRemoteAddr();
        if (clients.size() >= MAX_CLIENTS && !clients.containsKey(client)) {
            reject(response);
            return;
        }
        Window current = clients.compute(client, (key, old) -> old == null || old.minute != minute
                ? new Window(minute, 1) : new Window(minute, old.count + 1));
        if (current.count > MAX_PER_MINUTE) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType("application/json; charset=UTF-8");
        response.getWriter().write("{\"error\":\"Limite de 20 perguntas por minuto excedido.\"}");
    }

    private record Window(long minute, int count) {
    }
}
