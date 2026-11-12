package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProblemsStatusTextTest {

    private static final String REGISTRY_REASON_WITH_IDEA_AND_LSP = "no healthy provider for 'diagnostics': "
            + "idea (disconnected: not connected yet), lsp (no language server profile is configured; set "
            + "vintelli.lsp.profiles.<name>.command, for example vintelli.lsp.profiles.jdtls.command=jdtls)";

    @Test
    void aPlainInstallWithoutAConfiguredLanguageServerNamesTheSettingAndNothingElse() {
        String line = ProblemsStatusText.unavailable(REGISTRY_REASON_WITH_IDEA_AND_LSP, false);

        assertEquals(ProblemsStatusText.NO_PROVIDER_LINE, line);
        assertEquals("No diagnostics provider configured. Set vintelli.lsp.profiles.jdtls.command=jdtls, then "
                + "restart Vintelli (docs/language-servers.md).", line);
    }

    @Test
    void noRegisteredProviderIsTheSameNoProviderState() {
        assertEquals(ProblemsStatusText.NO_PROVIDER_LINE,
                ProblemsStatusText.unavailable("no healthy provider for 'diagnostics': ", true));
    }

    @Test
    void aConfiguredIdeaThatIsDisconnectedIsReportedAsNotReadyWithTheMissingServerHint() {
        String line = ProblemsStatusText.unavailable(REGISTRY_REASON_WITH_IDEA_AND_LSP, true);

        assertEquals("Diagnostics provider not ready. idea: disconnected: not connected yet. Set "
                + "vintelli.lsp.profiles.jdtls.command=jdtls to add one. Press R to retry.", line);
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
}
