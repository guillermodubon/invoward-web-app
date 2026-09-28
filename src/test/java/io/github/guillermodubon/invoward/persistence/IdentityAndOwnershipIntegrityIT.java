package io.github.guillermodubon.invoward.persistence;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.UUID;

import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.countRows;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertAnalysis;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertEmailVerificationToken;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertGuestSession;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertPasswordResetToken;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertUser;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IdentityAndOwnershipIntegrityIT extends PostgresIntegrityTestSupport {

    private static final String VALID_HASH = "a".repeat(64);

    @Test
    void emailUniquenessIgnoresCase() throws Exception {
        inTransaction(connection -> {
            insertUser(connection, "Case.Sensitive@example.test", "ACTIVE");
            assertRejected(connection,
                    c -> insertUser(c, "case.sensitive@EXAMPLE.test", "ACTIVE"));
        });
    }

    @Test
    void invalidUserStatusIsRejected() throws Exception {
        inTransaction(connection -> assertRejected(connection,
                c -> insertUser(c, "invalid-status@example.test", "LOCKED")));
    }

    @Test
    void malformedIdentityTokenHashesAreRejected() throws Exception {
        inTransaction(connection -> {
            UUID userId = insertUser(connection);
            assertRejected(connection,
                    c -> insertEmailVerificationToken(c, userId, "not-a-sha256-hash"));
            assertRejected(connection,
                    c -> insertPasswordResetToken(c, userId, "not-a-sha256-hash"));
        });
    }

    @Test
    void identityTokenHashesAreUniqueWithinEachTokenTable() throws Exception {
        inTransaction(connection -> {
            UUID firstUser = insertUser(connection);
            UUID secondUser = insertUser(connection);
            insertEmailVerificationToken(connection, firstUser, VALID_HASH);
            assertRejected(connection,
                    c -> insertEmailVerificationToken(c, secondUser, VALID_HASH));

            insertPasswordResetToken(connection, firstUser, VALID_HASH);
            assertRejected(connection,
                    c -> insertPasswordResetToken(c, secondUser, VALID_HASH));
        });
    }

    @Test
    void deletingUserCascadesEmailVerificationAndPasswordResetTokens() throws Exception {
        inTransaction(connection -> {
            UUID userId = insertUser(connection);
            insertEmailVerificationToken(connection, userId, VALID_HASH);
            insertPasswordResetToken(connection, userId, "b".repeat(64));

            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM invoward.users WHERE id = ?")) {
                statement.setObject(1, userId);
                assertEquals(1, statement.executeUpdate());
            }

            assertEquals(0, countRows(connection, "email_verification_tokens"));
            assertEquals(0, countRows(connection, "password_reset_tokens"));
        });
    }

    @Test
    void analysisRejectsBothOwnersAndNeitherOwner() throws Exception {
        inTransaction(connection -> {
            UUID userId = insertUser(connection);
            UUID guestId = insertGuestSession(connection);
            assertRejected(connection,
                    c -> insertAnalysis(c, userId, guestId, true));
            assertRejected(connection,
                    c -> insertAnalysis(c, null, null, false));
        });
    }

    @Test
    void userOwnedAnalysisIsAccepted() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertAnalysis(connection, insertUser(connection), null, false);
            assertEquals(1, countRows(connection, "analyses"));
            assertEquals(analysisId, onlyAnalysisId(connection));
        });
    }

    @Test
    void guestOwnedAnalysisWithExpiryIsAccepted() throws Exception {
        inTransaction(connection -> {
            UUID guestId = insertGuestSession(connection);
            UUID analysisId = insertAnalysis(connection, null, guestId, true);
            assertEquals(1, countRows(connection, "analyses"));
            assertEquals(analysisId, onlyAnalysisId(connection));
        });
    }

    @Test
    void guestOwnedAnalysisWithoutExpiryIsRejected() throws Exception {
        inTransaction(connection -> {
            UUID guestId = insertGuestSession(connection);
            assertRejected(connection,
                    c -> insertAnalysis(c, null, guestId, false));
        });
    }

    private static UUID onlyAnalysisId(java.sql.Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT id FROM invoward.analyses")) {
            result.next();
            return result.getObject(1, UUID.class);
        }
    }
}
