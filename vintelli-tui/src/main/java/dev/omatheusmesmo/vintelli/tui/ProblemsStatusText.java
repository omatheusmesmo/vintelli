package dev.omatheusmesmo.vintelli.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plain text of the Problems window states that the user reads: the unavailable line, which says the reason and
 * the next step instead of the registry wording, and the severity counts of the header. Pure functions, so the
 * rendering code only places the text.
 */
final class ProblemsStatusText {

    static final String NO_PROVIDER_LINE = "No language server found. Install jdtls and put it on the PATH, or set "
            + "vintelli.lsp.profiles.<name>.command (see docs/language-servers.md).";

    private static final String REGISTRY_PREFIX = "no healthy provider for '";
    private static final String NO_LANGUAGE_SERVER = "No language server found.";
    private static final String RETRY = "Press R to retry.";

    private ProblemsStatusText() {
    }

    /**
     * The one line the window shows when the diagnostics capability is unavailable. The registry wording
     * {@code no healthy provider for 'diagnostics': id (reason), ...} is turned into the reason of the providers
     * that were configured, or into the no-provider hint when no configured provider exists.
     */
    static String unavailable(String reason, boolean ideaConfigured) {
        List<ProviderReason> providers = providers(reason);
        if (providers == null) {
            return "Diagnostics unavailable: " + endWithPeriod(reason) + " " + RETRY;
        }
        List<ProviderReason> relevant = providers.stream()
                .filter(provider -> ideaConfigured || !CapabilityStateLine.IDEA_PROVIDER.equals(provider.identifier()))
                .toList();
        List<ProviderReason> unhealthy = relevant.stream().filter(provider -> !isUnconfigured(provider)).toList();
        if (unhealthy.isEmpty()) {
            return NO_PROVIDER_LINE;
        }
        String details = unhealthy.stream()
                .map(provider -> endWithPeriod(provider.identifier() + ": " + provider.reason()))
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        boolean missingLanguageServer = relevant.stream().anyMatch(ProblemsStatusText::isUnconfigured);
        String nextStep = missingLanguageServer ? " " + NO_PROVIDER_LINE : "";
        return "Diagnostics provider not ready. " + details + nextStep + " " + RETRY;
    }

    static String counts(int errors, int warnings, int others) {
        return plural(errors, "error", "errors") + ", " + plural(warnings, "warning", "warnings") + ", "
                + plural(others, "other", "others");
    }

    private static String plural(int count, String singular, String plural) {
        return String.format(Locale.ROOT, "%d %s", count, count == 1 ? singular : plural);
    }

    private static boolean isUnconfigured(ProviderReason provider) {
        return provider.reason().contains(NO_LANGUAGE_SERVER);
    }

    private static String endWithPeriod(String text) {
        String trimmed = text.strip();
        return trimmed.endsWith(".") || trimmed.endsWith("?") || trimmed.endsWith("!") ? trimmed : trimmed + ".";
    }

    /**
     * The providers named by a registry reason, or {@code null} when the reason has another shape. Entries are
     * separated by commas outside parentheses, because a provider reason can list settings with commas.
     */
    private static List<ProviderReason> providers(String reason) {
        if (reason == null || !reason.startsWith(REGISTRY_PREFIX)) {
            return null;
        }
        int separator = reason.indexOf("': ", REGISTRY_PREFIX.length());
        if (separator < 0) {
            return null;
        }
        List<ProviderReason> providers = new ArrayList<>();
        for (String entry : splitOutsideParentheses(reason.substring(separator + 3))) {
            if (entry.isBlank()) {
                continue;
            }
            int open = entry.indexOf(" (");
            if (open < 0 || !entry.endsWith(")")) {
                providers.add(new ProviderReason(entry.strip(), "unavailable"));
            } else {
                providers.add(new ProviderReason(entry.substring(0, open).strip(),
                        entry.substring(open + 2, entry.length() - 1).strip()));
            }
        }
        return providers;
    }

    private static List<String> splitOutsideParentheses(String text) {
        List<String> entries = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth = Math.max(0, depth - 1);
            } else if (current == ',' && depth == 0) {
                entries.add(text.substring(start, index));
                start = index + 1;
            }
        }
        entries.add(text.substring(start));
        return entries;
    }

    private record ProviderReason(String identifier, String reason) {
    }
}
