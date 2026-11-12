package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProblemsStatusTextTest {

    private static final String NO_LANGUAGE_SERVER = "No language server found. Install jdtls and put it on the PATH, "
            + "or set vintelli.lsp.profiles.<name>.command (see docs/language-servers.md).";
    private static final String REGISTRY_REASON_WITH_IDEA_AND_LSP = "no healthy provider for 'diagnostics': "
            + "idea (disconnected: not connected yet), lsp (" + NO_LANGUAGE_SERVER + ")";

    @Test
    void aPlainInstallWithoutAConfiguredLanguageServerNamesTheSettingAndNothingElse() {
        String line = ProblemsStatusText.unavailable(REGISTRY_REASON_WITH_IDEA_AND_LSP, false);

        assertEquals(ProblemsStatusText.NO_PROVIDER_LINE, line);
        assertEquals(NO_LANGUAGE_SERVER, line);
    }

    @Test
    void theMissingLanguageServerIsNamedOnceWithTheNextStep() {
        String line = ProblemsStatusText.unavailable(REGISTRY_REASON_WITH_IDEA_AND_LSP, true);

        assertEquals(1, occurrences(line, "No language server found"), line);
        assertEquals(1, occurrences(line, "docs/language-servers.md"), line);
        assertEquals(0, occurrences(line, "lsp:"), line);
        assertEquals(0, occurrences(line, "no healthy provider"), line);
    }

    @Test
    void noRegisteredProviderIsTheSameNoProviderState() {
        assertEquals(ProblemsStatusText.NO_PROVIDER_LINE,
                ProblemsStatusText.unavailable("no healthy provider for 'diagnostics': ", true));
    }

    @Test
    void aConfiguredIdeaThatIsDisconnectedIsReportedAsNotReadyWithTheMissingServerHint() {
        String line = ProblemsStatusText.unavailable(REGISTRY_REASON_WITH_IDEA_AND_LSP, true);

        assertEquals("Diagnostics provider not ready. idea: disconnected: not connected yet. " + NO_LANGUAGE_SERVER
                + " Press R to retry.", line);
    }

    @Test
    void aConfiguredProfileWhoseCommandIsMissingReportsItsReasonWithoutTheRegistryWording() {
        String line = ProblemsStatusText.unavailable("no healthy provider for 'diagnostics': lsp (the command "
                + "'jdtls' of profile 'jdtls' was not found; is the language server installed?)", false);

        assertEquals("Diagnostics provider not ready. lsp: the command 'jdtls' of profile 'jdtls' was not found; "
                + "is the language server installed? Press R to retry.", line);
    }

    @Test
    void anUnrecognisedReasonIsShownOnceWithTheRetryHint() {
        assertEquals("Diagnostics unavailable: not available in test. Press R to retry.",
                ProblemsStatusText.unavailable("not available in test", false));
    }

    @Test
    void severityCountsUseSingularAndPluralWords() {
        assertEquals("1 error, 0 warnings, 1 other", ProblemsStatusText.counts(1, 0, 1));
        assertEquals("2 errors, 3 warnings, 2 others", ProblemsStatusText.counts(2, 3, 2));
        assertEquals("0 errors, 1 warning, 0 others", ProblemsStatusText.counts(0, 1, 0));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }
}
