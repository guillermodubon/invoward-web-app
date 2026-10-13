package io.github.guillermodubon.invoward.reconciliation.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.reconciliation.application.port.DuplicateInvoiceCandidateRepository;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class DuplicateInvoiceCandidateRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final Instant BASE_TIME = Instant.parse("2026-10-01T12:00:00Z");

    @Autowired
    private DuplicateInvoiceCandidateRepository candidateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void registeredSearchIsOwnerScopedExcludesCurrentAnalysisAndReturnsFiveNewestCandidates() {
        UUID ownerId = createUser();
        UUID otherOwnerId = createUser();
        UUID currentAnalysisId = insertInvoiceAnalysis(
                ownerId, null, "INV-90", "acme", new BigDecimal("100.00"), "a".repeat(64),
                null, BASE_TIME.minusSeconds(1), null);

        List<UUID> sameOwnerCandidates = new ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            sameOwnerCandidates.add(insertInvoiceAnalysis(
                    ownerId, null, " inv-90 ", "acme", new BigDecimal("100.00"), hashFor(index),
                    LocalDate.of(2026, 10, 1), BASE_TIME.plusSeconds(index), null));
        }
        insertInvoiceAnalysis(otherOwnerId, null, "INV-90", "acme", new BigDecimal("100.00"),
                "a".repeat(64), LocalDate.of(2026, 10, 1), BASE_TIME.plusSeconds(20), null);
        insertInvoiceAnalysis(ownerId, null, "INV-91", "acme", new BigDecimal("100.00"),
                "c".repeat(64), LocalDate.of(2026, 10, 1), BASE_TIME.plusSeconds(21), null);

        List<DuplicateInvoiceCandidateRepository.CandidateRecord> found = candidateRepository.findCandidates(
                new RegisteredUserOwner(ownerId), currentAnalysisId, "a".repeat(64), "INV-90", NOW, 5);

        assertEquals(5, found.size());
        assertEquals(List.of(
                sameOwnerCandidates.get(5), sameOwnerCandidates.get(4), sameOwnerCandidates.get(3),
                sameOwnerCandidates.get(2), sameOwnerCandidates.get(1)),
                found.stream().map(DuplicateInvoiceCandidateRepository.CandidateRecord::analysisId).toList());
        assertFalse(found.stream().anyMatch(candidate -> candidate.analysisId().equals(currentAnalysisId)));
        assertFalse(found.stream().anyMatch(candidate -> candidate.invoiceFileSha256().equals("a".repeat(64))));
        assertEquals(LocalDate.of(2026, 10, 1), found.getFirst().documentDate());
        assertEquals(" inv-90 ", found.getFirst().invoiceNumber());
    }

    @Test
    void exactHashCandidateIsFoundWithoutInvoiceMetadataButAmountsAndDatesAloneAreNot() {
        UUID ownerId = createUser();
        UUID currentAnalysisId = insertInvoiceAnalysis(
                ownerId, null, "INV-CURRENT", "current supplier", new BigDecimal("42.00"),
                "d".repeat(64), LocalDate.of(2026, 9, 30), BASE_TIME, null);
        UUID sameHashCandidateId = insertInvoiceAnalysis(
                ownerId, null, null, null, null, "d".repeat(64), null, BASE_TIME.plusSeconds(1), null);
        insertInvoiceAnalysis(ownerId, null, "INV-OTHER", "another supplier", new BigDecimal("42.00"),
                "e".repeat(64), LocalDate.of(2026, 9, 30), BASE_TIME.plusSeconds(2), null);

        List<DuplicateInvoiceCandidateRepository.CandidateRecord> found = candidateRepository.findCandidates(
                new RegisteredUserOwner(ownerId), currentAnalysisId, "d".repeat(64), "INV-CURRENT", NOW, 5);

        assertEquals(List.of(sameHashCandidateId), found.stream()
                .map(DuplicateInvoiceCandidateRepository.CandidateRecord::analysisId).toList());
        assertTrue(found.getFirst().invoiceNumber() == null);
    }

    @Test
    void guestSearchIsScopedToItsSessionAndExcludesExpiredAnalyses() {
        UUID guestSessionId = createGuestSession();
        UUID otherGuestSessionId = createGuestSession();
        Instant guestExpiry = NOW.plusSeconds(3600);
        UUID currentAnalysisId = insertInvoiceAnalysis(
                null, guestSessionId, "INV-GUEST", "guest supplier", BigDecimal.TEN,
                "f".repeat(64), null, BASE_TIME, guestExpiry);
        UUID sameGuestCandidateId = insertInvoiceAnalysis(
                null, guestSessionId, "inv-guest", "guest supplier", BigDecimal.TEN,
                "1".repeat(64), null, BASE_TIME.plusSeconds(1), guestExpiry);
        insertInvoiceAnalysis(null, guestSessionId, "INV-GUEST", "guest supplier", BigDecimal.TEN,
                "f".repeat(64), null, BASE_TIME.plusSeconds(2), NOW.minusSeconds(1));
        insertInvoiceAnalysis(null, otherGuestSessionId, "INV-GUEST", "guest supplier", BigDecimal.TEN,
                "f".repeat(64), null, BASE_TIME.plusSeconds(3), guestExpiry);

        List<DuplicateInvoiceCandidateRepository.CandidateRecord> found = candidateRepository.findCandidates(
                new GuestSessionOwner(guestSessionId, guestExpiry), currentAnalysisId,
                "f".repeat(64), "INV-GUEST", NOW, 5);

        assertEquals(List.of(sameGuestCandidateId), found.stream()
                .map(DuplicateInvoiceCandidateRepository.CandidateRecord::analysisId).toList());
    }

    private UUID createUser() {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
    }

    private UUID createGuestSession() {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
    }

    private UUID insertInvoiceAnalysis(
            UUID userId,
            UUID guestSessionId,
            String invoiceNumber,
            String supplierKey,
            BigDecimal total,
            String invoiceHash,
            LocalDate documentDate,
            Instant createdAt,
            Instant expiresAt) {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) connection -> {
            UUID analysisId = DatabaseFixtures.insertAnalysis(
                    connection, userId, guestSessionId, guestSessionId != null);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE invoward.analyses
                    SET invoice_number = ?, supplier_key = ?, invoiced_total = ?,
                        created_at = ?, updated_at = ?, expires_at = ?
                    WHERE id = ?
                    """)) {
                update.setString(1, invoiceNumber);
                update.setString(2, supplierKey);
                update.setBigDecimal(3, total);
                update.setTimestamp(4, Timestamp.from(createdAt));
                update.setTimestamp(5, Timestamp.from(createdAt));
                if (expiresAt == null) {
                    update.setTimestamp(6, null);
                } else {
                    update.setTimestamp(6, Timestamp.from(expiresAt));
                }
                update.setObject(7, analysisId);
                update.executeUpdate();
            }

            UUID documentId = DatabaseFixtures.insertDocument(
                    connection, analysisId, "INVOICE", "INVOICE", "duplicate-test-" + UUID.randomUUID(),
                    invoiceHash, 128, 1);
            if (documentDate != null) {
                try (PreparedStatement extraction = connection.prepareStatement("""
                        INSERT INTO invoward.extracted_documents
                            (id, document_id, status, extraction_source, document_date,
                             extractor_version, schema_version, extracted_at, confirmed_at,
                             created_at, updated_at)
                        VALUES (?, ?, 'CONFIRMED', 'AI', ?, 'duplicate-test-v1', 1,
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)) {
                    extraction.setObject(1, UUID.randomUUID());
                    extraction.setObject(2, documentId);
                    extraction.setDate(3, java.sql.Date.valueOf(documentDate));
                    extraction.executeUpdate();
                }
            }
            return analysisId;
        });
    }

    private static String hashFor(int index) {
        return Integer.toHexString(index).repeat(64).substring(0, 64);
    }
}
