package io.github.guillermodubon.invoward.sharing.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkPage;
import io.github.guillermodubon.invoward.sharing.application.exception.DuplicateShareTokenHashException;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "invoward.email.provider=disabled")
@Transactional
class ShareLinkPersistenceIT {

    private static final Instant CREATED_AT = Instant.parse("2026-10-10T12:00:00Z");

    @Autowired
    private ShareLinkRepository shareLinkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void persistsAndFindsLinksByUniqueHashAndAnalysisScopedIdentity() {
        UUID analysisId = insertRegisteredAnalysis();
        ShareLink shareLink = newShareLink(
                UUID.randomUUID(), analysisId, "a".repeat(64), CREATED_AT, CREATED_AT.plusSeconds(3600), null);

        ShareLink persisted = shareLinkRepository.create(shareLink);

        assertEquals(shareLink, persisted);
        assertEquals(shareLink, shareLinkRepository.findByTokenHash(shareLink.tokenHash()).orElseThrow());
        assertEquals(shareLink, shareLinkRepository
                .findByAnalysisIdAndId(analysisId, shareLink.id()).orElseThrow());
        assertEquals(shareLink, shareLinkRepository
                .findByAnalysisIdAndIdForUpdate(analysisId, shareLink.id()).orElseThrow());
        assertTrue(shareLinkRepository.findByAnalysisIdAndId(UUID.randomUUID(), shareLink.id()).isEmpty());
    }

    @Test
    void countsOnlyUnrevokedLinksThatExpireStrictlyAfterNow() {
        UUID analysisId = insertRegisteredAnalysis();
        Instant now = CREATED_AT.plusSeconds(3600);
        shareLinkRepository.create(newShareLink(
                UUID.randomUUID(), analysisId, "b".repeat(64), CREATED_AT, now.plusSeconds(1), null));
        shareLinkRepository.create(newShareLink(
                UUID.randomUUID(), analysisId, "c".repeat(64), CREATED_AT, now, null));
        shareLinkRepository.create(newShareLink(
                UUID.randomUUID(), analysisId, "d".repeat(64), CREATED_AT, now.plusSeconds(100), now));

        assertEquals(1, shareLinkRepository.countActiveByAnalysisId(analysisId, now));
        assertEquals(0, shareLinkRepository.countActiveByAnalysisId(UUID.randomUUID(), now));
    }

    @Test
    void pagesAllLinkMetadataInCreatedAtThenIdDescendingOrder() {
        UUID analysisId = insertRegisteredAnalysis();
        UUID oldestId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID newerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID newestId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        shareLinkRepository.create(newShareLink(
                oldestId, analysisId, "e".repeat(64), CREATED_AT, CREATED_AT.plusSeconds(500), null));
        shareLinkRepository.create(newShareLink(
                newerId, analysisId, "f".repeat(64), CREATED_AT.plusSeconds(1), CREATED_AT.plusSeconds(500), null));
        shareLinkRepository.create(newShareLink(
                newestId, analysisId, "1".repeat(64), CREATED_AT.plusSeconds(1), CREATED_AT.plusSeconds(500), null));

        ShareLinkPage firstPage = shareLinkRepository.findPageByAnalysisId(analysisId, 0, 2);
        ShareLinkPage secondPage = shareLinkRepository.findPageByAnalysisId(analysisId, 1, 2);

        assertEquals(List.of(newestId, newerId), firstPage.content().stream().map(ShareLink::id).toList());
        assertEquals(List.of(oldestId), secondPage.content().stream().map(ShareLink::id).toList());
        assertEquals(3, firstPage.totalElements());
        assertEquals(2, firstPage.totalPages());
        assertTrue(firstPage.hasNext());
        assertFalse(secondPage.hasNext());
    }

    @Test
    void updatesOnlyTheLinkScopedToItsParentAnalysis() {
        UUID analysisId = insertRegisteredAnalysis();
        ShareLink shareLink = shareLinkRepository.create(newShareLink(
                UUID.randomUUID(), analysisId, "2".repeat(64), CREATED_AT, CREATED_AT.plusSeconds(3600), null));
        ShareLink revoked = shareLink.revoke(CREATED_AT.plusSeconds(10));

        assertEquals(revoked, shareLinkRepository.update(revoked).orElseThrow());
        assertEquals(revoked, shareLinkRepository.findByAnalysisIdAndId(analysisId, shareLink.id()).orElseThrow());

        ShareLink wrongParentCopy = new ShareLink(
                revoked.id(), UUID.randomUUID(), revoked.tokenHash(), revoked.createdAt(),
                revoked.expiresAt(), revoked.revokedAt());
        assertTrue(shareLinkRepository.update(wrongParentCopy).isEmpty());
        assertEquals(revoked, shareLinkRepository.findByAnalysisIdAndId(analysisId, shareLink.id()).orElseThrow());
    }

    @Test
    void updateCannotChangeImmutableTokenOrExpiryFields() {
        UUID analysisId = insertRegisteredAnalysis();
        ShareLink shareLink = shareLinkRepository.create(newShareLink(
                UUID.randomUUID(), analysisId, "8".repeat(64), CREATED_AT, CREATED_AT.plusSeconds(3600), null));
        ShareLink changedHash = new ShareLink(
                shareLink.id(), analysisId, "7".repeat(64), shareLink.createdAt(),
                shareLink.expiresAt(), null);
        assertThrows(InvalidDataAccessApiUsageException.class, () -> shareLinkRepository.update(changedHash));
    }

    @Test
    void databaseEnforcesUniqueTokenHash() {
        UUID analysisId = insertRegisteredAnalysis();
        String tokenHash = "3".repeat(64);
        shareLinkRepository.create(newShareLink(
                UUID.randomUUID(), analysisId, tokenHash, CREATED_AT, CREATED_AT.plusSeconds(3600), null));

        assertThrows(DuplicateShareTokenHashException.class,
                () -> shareLinkRepository.create(newShareLink(
                        UUID.randomUUID(), analysisId, tokenHash, CREATED_AT, CREATED_AT.plusSeconds(7200), null)));
    }

    @Test
    void requiredIndexesExistForHashLookupAndAnalysisOrderedPaging() {
        List<String> indexDefinitions = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'invoward' AND tablename = 'share_links'",
                String.class);
        assertTrue(indexDefinitions.stream().anyMatch(index ->
                index.contains("share_links_token_hash_uq") && index.contains("UNIQUE")
                        && index.contains("token_hash")));
        assertTrue(indexDefinitions.stream().anyMatch(index ->
                index.contains("share_links_analysis_created_idx")
                        && index.contains("analysis_id") && index.contains("created_at")));
        assertTrue(indexDefinitions.stream().anyMatch(index ->
                index.contains("share_links_expires_at_idx") && index.contains("expires_at")));
    }

    @Test
    void databaseRejectsUppercaseTokenHash() {
        UUID analysisId = insertRegisteredAnalysis();

        assertThrows(DataIntegrityViolationException.class,
                () -> insertRawShareLink(analysisId, "A".repeat(64), CREATED_AT, CREATED_AT.plusSeconds(10), null));
    }

    @Test
    void databaseRejectsNonIncreasingExpiry() {
        UUID analysisId = insertRegisteredAnalysis();

        assertThrows(DataIntegrityViolationException.class,
                () -> insertRawShareLink(analysisId, "4".repeat(64), CREATED_AT, CREATED_AT, null));
    }

    @Test
    void databaseRejectsRevocationBeforeCreation() {
        UUID analysisId = insertRegisteredAnalysis();

        assertThrows(DataIntegrityViolationException.class,
                () -> insertRawShareLink(analysisId, "5".repeat(64), CREATED_AT, CREATED_AT.plusSeconds(10),
                        CREATED_AT.minusSeconds(1)));
    }

    @Test
    void deletingAnalysisCascadesToShareLinkRows() {
        UUID analysisId = insertRegisteredAnalysis();
        shareLinkRepository.create(newShareLink(
                UUID.randomUUID(), analysisId, "6".repeat(64), CREATED_AT, CREATED_AT.plusSeconds(3600), null));

        assertEquals(1, countLinks(analysisId));
        assertEquals(1, jdbcTemplate.update("DELETE FROM invoward.analyses WHERE id = ?", analysisId));
        assertEquals(0, countLinks(analysisId));
    }

    private UUID insertRegisteredAnalysis() {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUserAnalysis);
    }

    private ShareLink newShareLink(
            UUID id, UUID analysisId, String tokenHash, Instant createdAt, Instant expiresAt, Instant revokedAt) {
        return new ShareLink(id, analysisId, tokenHash, createdAt, expiresAt, revokedAt);
    }

    private void insertRawShareLink(
            UUID analysisId, String tokenHash, Instant createdAt, Instant expiresAt, Instant revokedAt) {
        jdbcTemplate.update("""
                INSERT INTO invoward.share_links (id, analysis_id, token_hash, expires_at, revoked_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), analysisId, tokenHash, Timestamp.from(expiresAt),
                revokedAt == null ? null : Timestamp.from(revokedAt), Timestamp.from(createdAt));
    }

    private long countLinks(UUID analysisId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM invoward.share_links WHERE analysis_id = ?", Long.class, analysisId);
        assertNotNull(count);
        return count;
    }
}
