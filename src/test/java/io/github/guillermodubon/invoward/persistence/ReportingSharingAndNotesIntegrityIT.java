package io.github.guillermodubon.invoward.persistence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertAnalysisNote;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertGeneratedReport;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertShareLink;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertUserAnalysis;

class ReportingSharingAndNotesIntegrityIT extends PostgresIntegrityTestSupport {

    private static final String VALID_TOKEN_HASH = "c".repeat(64);

    @Test
    void anAnalysisCanHaveOnlyOneGeneratedReport() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            insertGeneratedReport(connection, analysisId, "reports/one.pdf");
            assertRejected(connection,
                    c -> insertGeneratedReport(c, analysisId, "reports/two.pdf"));
        });
    }

    @Test
    void generatedReportStorageKeyIsUniqueAcrossAnalyses() throws Exception {
        inTransaction(connection -> {
            insertGeneratedReport(connection, insertUserAnalysis(connection), "reports/shared.pdf");
            UUID otherAnalysisId = insertUserAnalysis(connection);
            assertRejected(connection,
                    c -> insertGeneratedReport(c, otherAnalysisId, "reports/shared.pdf"));
        });
    }

    @Test
    void shareTokenHashIsUnique() throws Exception {
        inTransaction(connection -> {
            insertShareLink(connection, insertUserAnalysis(connection), VALID_TOKEN_HASH, true);
            UUID otherAnalysisId = insertUserAnalysis(connection);
            assertRejected(connection,
                    c -> insertShareLink(c, otherAnalysisId, VALID_TOKEN_HASH, true));
        });
    }

    @Test
    void shareLinkExpiryMustBeAfterCreation() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            assertRejected(connection,
                    c -> insertShareLink(c, analysisId, VALID_TOKEN_HASH, false));
        });
    }

    @Test
    void anAnalysisCanHaveOnlyOnePrivateNote() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            insertAnalysisNote(connection, analysisId, "Internal note");
            assertRejected(connection,
                    c -> insertAnalysisNote(c, analysisId, "Second note"));
        });
    }

    @Test
    void analysisNoteCannotBeBlank() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            assertRejected(connection,
                    c -> insertAnalysisNote(c, analysisId, "   "));
        });
    }

    @Test
    void analysisNoteCannotExceedFiveThousandCharacters() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            assertRejected(connection,
                    c -> insertAnalysisNote(c, analysisId, "x".repeat(5001)));
        });
    }
}
