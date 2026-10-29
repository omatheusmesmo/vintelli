package dev.omatheusmesmo.vintelli.core.http;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Matching rules behind the loopback guard on the local MCP endpoint: a
 * request is only forwarded when its {@code Host} header names one of the
 * allowed hosts and, when a browser sends one, its {@code Origin} header is
 * on the allowed origin list. A request without an {@code Origin} header is
 * never rejected because command line clients and IDE MCP servers do not
 * send one.
 *
 * <p>The {@code Host} comparison strips the port (and IPv6 brackets) and is
 * case insensitive, so {@code localhost:8080}, {@code 127.0.0.1:8080} and
 * {@code [::1]:8080} normalize to the same name as the configured entries.
 * A {@code localhost.evil.example} style subdomain is rejected because the
 * match is exact, which is what stops DNS rebinding pages from reusing the
 * hostname {@code localhost} as a suffix. The {@code Origin} comparison is
 * also case insensitive and ignores a trailing slash.
 */
public final class AllowedHostsOrigins {

    private final Set<String> hosts;
    private final Set<String> origins;

    public AllowedHostsOrigins(Collection<String> allowedHosts, Collection<String> allowedOrigins) {
        this.hosts = Set.copyOf(normalized(allowedHosts, true));
        this.origins = Set.copyOf(normalized(allowedOrigins, false));
    }

    public boolean hostAllowed(String host) {
        return host != null && hosts.contains(authorityHost(host));
    }

    public boolean originAllowed(String origin) {
        return origin == null || origins.contains(normalizeOrigin(origin));
    }

    private static List<String> normalized(Collection<String> values, boolean hostsToo) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.isBlank()) {
                continue;
            }
            result.add(hostsToo ? authorityHost(value) : normalizeOrigin(value));
        }
        return result;
    }

    private static String authorityHost(String host) {
        String value = host.trim().toLowerCase(Locale.ROOT);
        int closingBracket = value.indexOf(']');
        if (value.startsWith("[") && closingBracket > 0) {
            return value.substring(1, closingBracket);
        }
        int colon = value.indexOf(':');
        if (colon == value.lastIndexOf(':') && isPort(value.substring(colon + 1))) {
            return value.substring(0, colon);
        }
        return value;
    }

    private static boolean isPort(String tail) {
        if (tail.isEmpty()) {
            return false;
        }
        for (int i = 0; i < tail.length(); i++) {
            if (tail.charAt(i) < '0' || tail.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static String normalizeOrigin(String origin) {
        String value = origin.trim().toLowerCase(Locale.ROOT);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
