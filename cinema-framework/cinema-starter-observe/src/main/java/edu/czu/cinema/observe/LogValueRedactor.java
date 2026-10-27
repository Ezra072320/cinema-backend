package edu.czu.cinema.observe;

import java.net.URI;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Final output safeguard for known credentials; callers still must avoid logging user payloads. */
final class LogValueRedactor {
    private static final Pattern JWT = Pattern.compile("(?:Bearer\\s+)?eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern NAMED = Pattern.compile("(?i)((?:password|passwd|access[_-]?token|api[_-]?key|secret|captchaCode|authorization)\\s*[=:]\\s*[\\\"']?)([^\\s,;&}\\\"']+)");
    private static final Pattern URL = Pattern.compile("https?://[^\\s\\\"'<>]+", Pattern.CASE_INSENSITIVE);
    private final List<String> secrets;

    LogValueRedactor(Collection<String> secrets) {
        this.secrets = secrets.stream().filter(v -> v != null && v.length() >= 6)
            .distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    String redact(String value) {
        if (value == null) return "";
        for (String secret : secrets) value = value.replace(secret, "[redacted]");
        value = JWT.matcher(value).replaceAll("[redacted-jwt]");
        value = NAMED.matcher(value).replaceAll("$1[redacted]");
        value = URL.matcher(value).replaceAll(match -> cleanUrl(match.group()));
        return value.length() > 16384 ? value.substring(0, 16384) + "[truncated]" : value;
    }

    private static String cleanUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null) return "[redacted-url]";
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toASCIIString();
        } catch (Exception ignored) {
            return "[redacted-url]";
        }
    }
}
