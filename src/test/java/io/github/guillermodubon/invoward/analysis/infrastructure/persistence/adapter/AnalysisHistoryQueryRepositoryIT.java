package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryPage;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryQuery;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistorySort;
import io.github.guillermodubon.invoward.analysis.application.model.HistoryDiscrepancyType;
import io.github.guillermodubon.invoward.analysis.application.service.ListAnalysisHistoryService;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.application.exception.InvalidAnalysisHistoryQueryException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisHistoryQueryRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "invoward.email.provider=disabled")
@Transactional
class AnalysisHistoryQueryRepositoryIT {

    private static final Instant BASE_TIME = Instant.parse("2026-09-30T12:00:00Z");

    @Autowired
    private AnalysisHistoryQueryRepository queryRepository;

    @Autowired
    private ListAnalysisHistoryService listAnalysisHistoryService;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void pagesOnlyRegisteredUsersAndScopesContentAndCountAwayFromOtherUsersAndGuests() throws Exception {
        UUID firstUserId = insertUser();
        UUID secondUserId = insertUser();
        UUID guestSessionId = insertGuestSession();
        List<UUID> firstUserAnalysisIds = persistRegisteredAnalyses(firstUserId, 23);
        persistRegisteredAnalyses(secondUserId, 4);
        persistGuestAnalysis(guestSessionId);

        AnalysisHistoryPage firstPage = listAnalysisHistoryService.list(firstUserId, AnalysisHistoryQuery.defaults());

        assertEquals(0, firstPage.page());
        assertEquals(20, firstPage.size());
        assertEquals(20, firstPage.content().size());
        assertEquals(23, firstPage.totalElements());
        assertEquals(2, firstPage.totalPages());
        assertTrue(firstPage.hasNext());
        assertFalse(firstPage.hasPrevious());
        assertEquals(firstUserAnalysisIds.get(22), firstPage.content().getFirst().id());
        assertTrue(firstPage.content().stream().allMatch(item -> firstUserAnalysisIds.contains(item.id())));

        AnalysisHistoryPage secondPage = queryRepository.findForRegisteredUser(firstUserId, query(1, 20));

        assertEquals(1, secondPage.page());
        assertEquals(3, secondPage.content().size());
        assertEquals(23, secondPage.totalElements());
        assertEquals(2, secondPage.totalPages());
        assertFalse(secondPage.hasNext());
        assertTrue(secondPage.hasPrevious());
        assertEquals(firstUserAnalysisIds.get(2), secondPage.content().getFirst().id());
        assertEquals(firstUserAnalysisIds.get(0), secondPage.content().getLast().id());
    }

    @Test
    void supportsMaximumPageSizeOutOfRangePagesAndEmptyRegisteredHistory() throws Exception {
        UUID userId = insertUser();
        List<UUID> analysisIds = persistRegisteredAnalyses(userId, 23);

        AnalysisHistoryPage maximumPage = queryRepository.findForRegisteredUser(userId, query(0, 100));
        assertEquals(100, maximumPage.size());
        assertEquals(23, maximumPage.content().size());
        assertEquals(23, maximumPage.totalElements());
        assertEquals(1, maximumPage.totalPages());
        assertEquals(analysisIds.get(22), maximumPage.content().getFirst().id());

        AnalysisHistoryPage beyondEnd = queryRepository.findForRegisteredUser(userId, query(1000, 20));
        assertTrue(beyondEnd.content().isEmpty());
        assertEquals(23, beyondEnd.totalElements());
        assertEquals(2, beyondEnd.totalPages());
        assertFalse(beyondEnd.hasNext());
        assertTrue(beyondEnd.hasPrevious());

        AnalysisHistoryPage empty = queryRepository.findForRegisteredUser(UUID.randomUUID(), AnalysisHistoryQuery.defaults());
        assertTrue(empty.content().isEmpty());
        assertEquals(0, empty.totalElements());
        assertEquals(0, empty.totalPages());
        assertFalse(empty.hasNext());
        assertFalse(empty.hasPrevious());
    }

    @Test
    void projectionPreservesNullableFieldsForAnAnalysisStillProcessing() throws Exception {
        UUID userId = insertUser();
        UUID analysisId = persistRegisteredAnalyses(userId, 1).getFirst();

        AnalysisHistoryItem result = queryRepository.findForRegisteredUser(
                userId, AnalysisHistoryQuery.defaults()).content().getFirst();

        assertEquals(analysisId, result.id());
        assertEquals("CREATED", result.status().name());
        assertEquals("PENDING", result.reviewStatus().name());
        assertNull(result.reconciliationStatus());
        assertNull(result.reviewedAt());
        assertNull(result.supplierName());
        assertNull(result.referenceTotal());
        assertNull(result.invoicedTotal());
        assertNull(result.difference());
        assertNull(result.completedAt());
    }

    @Test
    void searchesSupplierInvoiceAndReferenceAsSubstringAndCombinesSupplierFilterWithAnd() throws Exception {
        UUID userId = insertUser();
        UUID supplierMatch = persistAnalysisWithLookup(userId, "Café Needles Ltd", "INV-A", "REF-A");
        UUID invoiceMatch = persistAnalysisWithLookup(userId, "Other Supplier", "needle-invoice-B", "REF-B");
        UUID referenceMatch = persistAnalysisWithLookup(userId, "Other Supplier", "INV-C", "needle-reference-C");
        UUID multipleFieldMatch = persistAnalysisWithLookup(
                userId, "Café Needle Parts", "needle-invoice-D", "REF-D");

        AnalysisHistoryPage supplierSearch = queryRepository.findForRegisteredUser(userId, query("NEEDLES", null));
        assertEquals(List.of(supplierMatch), ids(supplierSearch));

        AnalysisHistoryPage invoiceSearch = queryRepository.findForRegisteredUser(userId, query("needle-invoice", null));
        assertEquals(2, invoiceSearch.totalElements());
        assertTrue(ids(invoiceSearch).containsAll(List.of(invoiceMatch, multipleFieldMatch)));

        AnalysisHistoryPage referenceSearch = queryRepository.findForRegisteredUser(userId, query("NEEDLE-REFERENCE", null));
        assertEquals(List.of(referenceMatch), ids(referenceSearch));

        AnalysisHistoryPage anyFieldSearch = queryRepository.findForRegisteredUser(userId, query("needle", null));
        assertEquals(4, anyFieldSearch.totalElements());
        assertEquals(4, ids(anyFieldSearch).stream().distinct().count());
        assertTrue(ids(anyFieldSearch).containsAll(
                List.of(supplierMatch, invoiceMatch, referenceMatch, multipleFieldMatch)));

        AnalysisHistoryPage constrained = queryRepository.findForRegisteredUser(userId, query("needle", "CAFÉ"));
        assertEquals(2, constrained.totalElements());
        assertTrue(ids(constrained).containsAll(List.of(supplierMatch, multipleFieldMatch)));
    }

    @Test
    void treatsLikeWildcardsEscapeCharactersAndInjectionLookingSearchAsLiteralText() throws Exception {
        UUID userId = insertUser();
        UUID literalMatch = persistAnalysisWithLookup(userId, "Supplier A%_\\B", "INV-1", "REF-1");
        persistAnalysisWithLookup(userId, "Unrelated Supplier", "INV-2", "REF-2");

        AnalysisHistoryPage wildcardSearch = queryRepository.findForRegisteredUser(userId, query("%_\\", null));
        assertEquals(List.of(literalMatch), ids(wildcardSearch));
        assertEquals(0, queryRepository.findForRegisteredUser(
                userId, query("%' OR 1=1 --", null)).totalElements());
    }

    @Test
    void blankSearchIsIgnoredAndOwnerPredicatesStillScopeFilteredContentAndCount() throws Exception {
        UUID userId = insertUser();
        UUID anotherUserId = insertUser();
        UUID guestSessionId = insertGuestSession();
        UUID owned = persistAnalysisWithLookup(userId, "Shared Needle Supplier", "INV-OWN", "REF-OWN");
        persistAnalysisWithLookup(anotherUserId, "Shared Needle Supplier", "INV-OTHER", "REF-OTHER");
        UUID guest = persistGuestAnalysis(guestSessionId);
        setLookupFields(guest, "Shared Needle Supplier", "INV-GUEST", "REF-GUEST");

        AnalysisHistoryPage blank = queryRepository.findForRegisteredUser(userId, query("   ", null));
        assertEquals(1, blank.totalElements());
        assertEquals(List.of(owned), ids(blank));

        AnalysisHistoryPage filtered = queryRepository.findForRegisteredUser(
                userId, query("needle", "shared needle"));
        assertEquals(1, filtered.totalElements());
        assertEquals(List.of(owned), ids(filtered));
    }

    @Test
    void acceptsSearchAndSupplierAtTheirCodePointLimitsAndRejectsLongerValues() throws Exception {
        UUID userId = insertUser();
        String invoiceNumber = "i".repeat(120);
        String supplierName = "s".repeat(240);
        UUID analysisId = persistAnalysisWithLookup(userId, supplierName, invoiceNumber, "REF-LIMIT");

        assertEquals(List.of(analysisId), ids(queryRepository.findForRegisteredUser(
                userId, query(invoiceNumber, null))));
        assertEquals(List.of(analysisId), ids(queryRepository.findForRegisteredUser(
                userId, query(null, supplierName))));
        assertThrows(InvalidAnalysisHistoryQueryException.class, () -> query("i".repeat(121), null));
        assertThrows(InvalidAnalysisHistoryQueryException.class, () -> query(null, "s".repeat(241)));
    }

    @Test
    void filtersReconciliationProcessingAndReviewStatusesAndCurrencyIndependently() throws Exception {
        UUID userId = insertUser();
        UUID matchedCompletedReviewedUsd = persistAnalysisWithLookup(userId, "Supplier A", "INV-A", "REF-A");
        UUID reviewRequiredFailedPendingEur = persistAnalysisWithLookup(userId, "Supplier B", "INV-B", "REF-B");
        UUID noReconciliationCreatedPendingUsd = persistAnalysisWithLookup(userId, "Supplier C", "INV-C", "REF-C");
        UUID matchedFailedReviewedWithoutCurrency = persistAnalysisWithLookup(
                userId, "Supplier D", "INV-D", "REF-D");

        setHistoryState(
                matchedCompletedReviewedUsd,
                AnalysisReconciliationStatus.MATCHED,
                AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.REVIEWED,
                "USD");
        setHistoryState(
                reviewRequiredFailedPendingEur,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                AnalysisStatus.FAILED,
                AnalysisReviewStatus.PENDING,
                "EUR");
        setHistoryState(
                noReconciliationCreatedPendingUsd,
                null,
                AnalysisStatus.CREATED,
                AnalysisReviewStatus.PENDING,
                "USD");
        setHistoryState(
                matchedFailedReviewedWithoutCurrency,
                AnalysisReconciliationStatus.MATCHED,
                AnalysisStatus.FAILED,
                AnalysisReviewStatus.REVIEWED,
                null);

        assertEquals(4, queryRepository.findForRegisteredUser(userId, AnalysisHistoryQuery.defaults()).totalElements());
        assertIds(Set.of(matchedCompletedReviewedUsd, matchedFailedReviewedWithoutCurrency),
                queryRepository.findForRegisteredUser(
                        userId, query(AnalysisReconciliationStatus.MATCHED, null, null, null)));
        assertIds(Set.of(reviewRequiredFailedPendingEur),
                queryRepository.findForRegisteredUser(
                        userId, query(AnalysisReconciliationStatus.REVIEW_REQUIRED, null, null, null)));
        assertIds(Set.of(matchedCompletedReviewedUsd),
                queryRepository.findForRegisteredUser(userId, query(null, AnalysisStatus.COMPLETED, null, null)));
        assertIds(Set.of(reviewRequiredFailedPendingEur, matchedFailedReviewedWithoutCurrency),
                queryRepository.findForRegisteredUser(userId, query(null, AnalysisStatus.FAILED, null, null)));
        assertIds(Set.of(reviewRequiredFailedPendingEur, noReconciliationCreatedPendingUsd),
                queryRepository.findForRegisteredUser(
                        userId, query(null, null, AnalysisReviewStatus.PENDING, null)));
        assertIds(Set.of(matchedCompletedReviewedUsd, matchedFailedReviewedWithoutCurrency),
                queryRepository.findForRegisteredUser(
                        userId, query(null, null, AnalysisReviewStatus.REVIEWED, null)));
        assertIds(Set.of(matchedCompletedReviewedUsd, noReconciliationCreatedPendingUsd),
                queryRepository.findForRegisteredUser(userId, query(null, null, null, "usd")));
        assertIds(Set.of(reviewRequiredFailedPendingEur),
                queryRepository.findForRegisteredUser(userId, query(null, null, null, "EUR")));
    }

    @Test
    void combinesTypedStatusAndCurrencyFiltersAndKeepsFilteredCountsOwnerScoped() throws Exception {
        UUID userId = insertUser();
        UUID anotherUserId = insertUser();
        UUID guestSessionId = insertGuestSession();
        UUID exactMatch = persistAnalysisWithLookup(userId, "Owner Supplier", "INV-OWN", "REF-OWN");
        UUID differentReviewStatus = persistAnalysisWithLookup(userId, "Owner Supplier", "INV-OWN-2", "REF-OWN-2");
        UUID differentCurrency = persistAnalysisWithLookup(userId, "Owner Supplier", "INV-OWN-3", "REF-OWN-3");
        UUID anotherUsersMatch = persistAnalysisWithLookup(
                anotherUserId, "Other Supplier", "INV-OTHER", "REF-OTHER");
        UUID guestMatch = persistGuestAnalysis(guestSessionId);

        setHistoryState(exactMatch, AnalysisReconciliationStatus.MATCHED,
                AnalysisStatus.COMPLETED, AnalysisReviewStatus.REVIEWED, "USD");
        setHistoryState(differentReviewStatus, AnalysisReconciliationStatus.MATCHED,
                AnalysisStatus.COMPLETED, AnalysisReviewStatus.PENDING, "USD");
        setHistoryState(differentCurrency, AnalysisReconciliationStatus.MATCHED,
                AnalysisStatus.COMPLETED, AnalysisReviewStatus.REVIEWED, "EUR");
        setHistoryState(anotherUsersMatch, AnalysisReconciliationStatus.MATCHED,
                AnalysisStatus.COMPLETED, AnalysisReviewStatus.REVIEWED, "USD");
        setHistoryState(guestMatch, AnalysisReconciliationStatus.MATCHED,
                AnalysisStatus.COMPLETED, AnalysisReviewStatus.REVIEWED, "USD");

        AnalysisHistoryPage filtered = queryRepository.findForRegisteredUser(userId, query(
                AnalysisReconciliationStatus.MATCHED,
                AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.REVIEWED,
                " usd "));

        assertEquals(1, filtered.totalElements());
        assertEquals(1, filtered.totalPages());
        assertIds(Set.of(exactMatch), filtered);
    }

    @Test
    void filtersDiscrepancyTypesWithExistsWithoutDuplicatingRowsOrCounts() throws Exception {
        UUID userId = insertUser();
        UUID anotherUserId = insertUser();
        UUID guestSessionId = insertGuestSession();
        LocalDate filterDate = LocalDate.of(2026, 9, 30);

        UUID singleMatch = persistRegisteredAnalysisAt(userId, BASE_TIME);
        UUID multipleMatches = persistRegisteredAnalysisAt(userId, BASE_TIME.plusSeconds(1));
        UUID withoutDiscrepancy = persistRegisteredAnalysisAt(userId, BASE_TIME.plusSeconds(2));
        UUID otherDiscrepancyType = persistRegisteredAnalysisAt(userId, BASE_TIME.plusSeconds(3));
        UUID wrongReconciliationStatus = persistRegisteredAnalysisAt(userId, BASE_TIME.plusSeconds(4));
        UUID outsideDate = persistRegisteredAnalysisAt(userId, Instant.parse("2026-10-01T00:00:00Z"));
        UUID anotherOwnersMatch = persistRegisteredAnalysisAt(anotherUserId, BASE_TIME.plusSeconds(5));
        UUID guestMatch = persistGuestAnalysisAt(guestSessionId, BASE_TIME.plusSeconds(6));

        for (UUID analysisId : List.of(singleMatch, multipleMatches, withoutDiscrepancy,
                otherDiscrepancyType, outsideDate)) {
            setHistoryState(analysisId, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                    AnalysisStatus.COMPLETED, AnalysisReviewStatus.PENDING, "USD");
        }
        setHistoryState(wrongReconciliationStatus, AnalysisReconciliationStatus.MATCHED,
                AnalysisStatus.COMPLETED, AnalysisReviewStatus.PENDING, "USD");
        setHistoryState(anotherOwnersMatch, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                AnalysisStatus.COMPLETED, AnalysisReviewStatus.PENDING, "USD");
        setHistoryState(guestMatch, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                AnalysisStatus.COMPLETED, AnalysisReviewStatus.PENDING, "USD");

        insertDiscrepancy(singleMatch, "UNIT_PRICE_MISMATCH");
        insertDiscrepancy(multipleMatches, "UNIT_PRICE_MISMATCH");
        insertDiscrepancy(multipleMatches, "UNIT_PRICE_MISMATCH");
        insertDiscrepancy(withoutDiscrepancy, "QUANTITY_MISMATCH");
        insertDiscrepancy(otherDiscrepancyType, "QUANTITY_MISMATCH");
        insertDiscrepancy(wrongReconciliationStatus, "UNIT_PRICE_MISMATCH");
        insertDiscrepancy(outsideDate, "UNIT_PRICE_MISMATCH");
        insertDiscrepancy(anotherOwnersMatch, "UNIT_PRICE_MISMATCH");
        insertDiscrepancy(guestMatch, "UNIT_PRICE_MISMATCH");

        AnalysisHistoryQuery firstPageQuery = queryWithDiscrepancy(
                0, 1, HistoryDiscrepancyType.UNIT_PRICE_MISMATCH,
                AnalysisReconciliationStatus.REVIEW_REQUIRED, filterDate, filterDate);
        AnalysisHistoryQuery secondPageQuery = queryWithDiscrepancy(
                1, 1, HistoryDiscrepancyType.UNIT_PRICE_MISMATCH,
                AnalysisReconciliationStatus.REVIEW_REQUIRED, filterDate, filterDate);

        AnalysisHistoryPage firstPage = queryRepository.findForRegisteredUser(userId, firstPageQuery);
        AnalysisHistoryPage secondPage = queryRepository.findForRegisteredUser(userId, secondPageQuery);

        assertEquals(1, firstPage.content().size());
        assertEquals(1, secondPage.content().size());
        assertEquals(2, firstPage.totalElements());
        assertEquals(2, secondPage.totalElements());
        assertEquals(2, firstPage.totalPages());
        assertEquals(2, Set.copyOf(List.of(firstPage.content().getFirst().id(),
                secondPage.content().getFirst().id())).size());
        assertEquals(Set.of(singleMatch, multipleMatches),
                Set.copyOf(List.of(firstPage.content().getFirst().id(), secondPage.content().getFirst().id())));

        AnalysisHistoryPage noMatches = queryRepository.findForRegisteredUser(userId, queryWithDiscrepancy(
                0, 20, HistoryDiscrepancyType.DISCOUNT_MISMATCH, null, null, null));
        assertTrue(noMatches.content().isEmpty());
        assertEquals(0, noMatches.totalElements());
    }

    @Test
    void appliesEveryWhitelistedSortWithNullsLastAndStablePagedTieBreakers() throws Exception {
        UUID userId = insertUser();
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        UUID c = new UUID(0, 3);
        UUID d = new UUID(0, 4);
        UUID e = new UUID(0, 5);
        UUID f = new UUID(0, 6);

        persistSortableAnalysis(userId, a, BASE_TIME,
                new BigDecimal("10.0000"), new BigDecimal("200.0000"), "beta");
        persistSortableAnalysis(userId, b, BASE_TIME.plusSeconds(1),
                new BigDecimal("-10.0000"), new BigDecimal("100.0000"), "ALPHA");
        persistSortableAnalysis(userId, c, BASE_TIME.plusSeconds(2),
                new BigDecimal("5.0000"), null, "zulu");
        persistSortableAnalysis(userId, d, BASE_TIME.plusSeconds(3),
                new BigDecimal("5.0000"), new BigDecimal("200.0000"), "alpha");
        persistSortableAnalysis(userId, e, BASE_TIME.plusSeconds(3),
                new BigDecimal("-5.0000"), new BigDecimal("200.0000"), "ALPHA");
        persistSortableAnalysis(userId, f, BASE_TIME.plusSeconds(4), null, null, null);

        List<SortExpectation> expectations = List.of(
                new SortExpectation(AnalysisHistorySort.CREATED_AT_DESC, List.of(f, e, d, c, b, a)),
                new SortExpectation(AnalysisHistorySort.CREATED_AT_ASC, List.of(a, b, c, e, d, f)),
                new SortExpectation(AnalysisHistorySort.DIFFERENCE_DESC, List.of(b, a, e, d, c, f)),
                new SortExpectation(AnalysisHistorySort.DIFFERENCE_ASC, List.of(e, d, c, b, a, f)),
                new SortExpectation(AnalysisHistorySort.INVOICED_TOTAL_DESC, List.of(e, d, a, b, f, c)),
                new SortExpectation(AnalysisHistorySort.INVOICED_TOTAL_ASC, List.of(b, e, d, a, f, c)),
                new SortExpectation(AnalysisHistorySort.SUPPLIER_NAME_ASC, List.of(e, d, b, a, c, f)),
                new SortExpectation(AnalysisHistorySort.SUPPLIER_NAME_DESC, List.of(c, a, e, d, b, f)));

        for (SortExpectation expectation : expectations) {
            List<UUID> pagedIds = new ArrayList<>();
            for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
                AnalysisHistoryPage page = queryRepository.findForRegisteredUser(
                        userId, query(pageNumber, 2, expectation.sort()));
                assertEquals(6, page.totalElements(), expectation.sort().parameterValue());
                assertEquals(3, page.totalPages(), expectation.sort().parameterValue());
                pagedIds.addAll(ids(page));
            }

            assertEquals(expectation.expectedIds(), pagedIds, expectation.sort().parameterValue());
            assertEquals(6, pagedIds.stream().distinct().count(), expectation.sort().parameterValue());
        }
    }

    @Test
    void filtersCreationDateAsInclusiveUtcDaysAcrossLeapDayBoundariesAndOwners() throws Exception {
        UUID userId = insertUser();
        UUID anotherUserId = insertUser();
        LocalDate leapDay = LocalDate.of(2024, 2, 29);

        UUID beforeStart = persistRegisteredAnalysisAt(
                userId, Instant.parse("2024-02-28T23:59:59.999999Z"));
        UUID atStart = persistRegisteredAnalysisAt(userId, Instant.parse("2024-02-29T00:00:00Z"));
        UUID atLastPostgresMicrosecond = persistRegisteredAnalysisAt(
                userId, Instant.parse("2024-02-29T23:59:59.999999Z"));
        UUID atExclusiveEnd = persistRegisteredAnalysisAt(userId, Instant.parse("2024-03-01T00:00:00Z"));
        persistRegisteredAnalysisAt(anotherUserId, Instant.parse("2024-02-29T12:00:00Z"));
        UUID guestSessionId = insertGuestSession();
        persistGuestAnalysisAt(guestSessionId, Instant.parse("2024-02-29T12:00:00Z"));

        AnalysisHistoryPage oneUtcDay = queryRepository.findForRegisteredUser(
                userId, queryByDateAndMagnitude(leapDay, leapDay, null, null));
        assertIds(Set.of(atStart, atLastPostgresMicrosecond), oneUtcDay);

        AnalysisHistoryPage fromInclusive = queryRepository.findForRegisteredUser(
                userId, queryByDateAndMagnitude(leapDay, null, null, null));
        assertIds(Set.of(atStart, atLastPostgresMicrosecond, atExclusiveEnd), fromInclusive);

        AnalysisHistoryPage toInclusive = queryRepository.findForRegisteredUser(
                userId, queryByDateAndMagnitude(null, leapDay, null, null));
        assertIds(Set.of(beforeStart, atStart, atLastPostgresMicrosecond), toInclusive);
    }

    @Test
    void dateFilteringUsesUtcBoundariesWithoutDaylightSavingTimeShift() throws Exception {
        UUID userId = insertUser();
        LocalDate daylightSavingTransitionDay = LocalDate.of(2026, 3, 8);
        UUID utcStart = persistRegisteredAnalysisAt(userId, Instant.parse("2026-03-08T00:00:00Z"));
        UUID immediatelyBeforeTypicalUsDstTransition = persistRegisteredAnalysisAt(
                userId, Instant.parse("2026-03-08T06:59:59.999999Z"));
        UUID immediatelyAfterTypicalUsDstTransition = persistRegisteredAnalysisAt(
                userId, Instant.parse("2026-03-08T07:00:00Z"));
        UUID nextUtcDay = persistRegisteredAnalysisAt(userId, Instant.parse("2026-03-09T00:00:00Z"));

        AnalysisHistoryPage day = queryRepository.findForRegisteredUser(
                userId, queryByDateAndMagnitude(daylightSavingTransitionDay, daylightSavingTransitionDay, null, null));

        assertIds(Set.of(utcStart, immediatelyBeforeTypicalUsDstTransition,
                immediatelyAfterTypicalUsDstTransition), day);
        assertFalse(ids(day).contains(nextUtcDay));
    }

    @Test
    void filtersInclusiveAbsoluteDifferenceMagnitudeAndExcludesNullAcrossOwners() throws Exception {
        UUID userId = insertUser();
        UUID anotherUserId = insertUser();
        UUID guestSessionId = insertGuestSession();
        UUID positiveBoundary = persistAnalysisWithLookup(userId, "Supplier", "INV-POS", "REF-POS");
        UUID negativeBoundary = persistAnalysisWithLookup(userId, "Supplier", "INV-NEG", "REF-NEG");
        UUID zero = persistAnalysisWithLookup(userId, "Supplier", "INV-ZERO", "REF-ZERO");
        UUID justInsideUpperBound = persistAnalysisWithLookup(userId, "Supplier", "INV-IN", "REF-IN");
        UUID justOutsideUpperBound = persistAnalysisWithLookup(userId, "Supplier", "INV-OUT", "REF-OUT");
        UUID noDifference = persistAnalysisWithLookup(userId, "Supplier", "INV-NULL", "REF-NULL");
        UUID otherOwnerAtBoundary = persistAnalysisWithLookup(
                anotherUserId, "Supplier", "INV-OTHER", "REF-OTHER");
        UUID guestAtBoundary = persistGuestAnalysis(guestSessionId);

        setDifference(positiveBoundary, new BigDecimal("10.0000"));
        setDifference(negativeBoundary, new BigDecimal("-10.0000"));
        setDifference(zero, BigDecimal.ZERO.setScale(4));
        setDifference(justInsideUpperBound, new BigDecimal("9.9999"));
        setDifference(justOutsideUpperBound, new BigDecimal("-10.0001"));
        setDifference(otherOwnerAtBoundary, new BigDecimal("10.0000"));
        setDifference(guestAtBoundary, new BigDecimal("-10.0000"));

        AnalysisHistoryPage exactMagnitude = queryRepository.findForRegisteredUser(
                userId, queryByDateAndMagnitude(null, null,
                        new BigDecimal("10.0000"), new BigDecimal("10.0000")));
        assertIds(Set.of(positiveBoundary, negativeBoundary), exactMagnitude);
        assertEquals(new BigDecimal("10.0000"), exactMagnitude.content().stream()
                .filter(item -> item.id().equals(positiveBoundary)).findFirst().orElseThrow().difference());
        assertEquals(new BigDecimal("-10.0000"), exactMagnitude.content().stream()
                .filter(item -> item.id().equals(negativeBoundary)).findFirst().orElseThrow().difference());

        assertIds(Set.of(zero), queryRepository.findForRegisteredUser(
                userId, queryByDateAndMagnitude(null, null, BigDecimal.ZERO, BigDecimal.ZERO)));
        assertIds(Set.of(positiveBoundary, negativeBoundary, justOutsideUpperBound),
                queryRepository.findForRegisteredUser(
                        userId, queryByDateAndMagnitude(null, null, new BigDecimal("10"), null)));
        assertIds(Set.of(positiveBoundary, negativeBoundary, zero, justInsideUpperBound),
                queryRepository.findForRegisteredUser(
                        userId, queryByDateAndMagnitude(null, null, null, new BigDecimal("10"))));

        assertFalse(ids(exactMagnitude).contains(noDifference));
    }

    private List<UUID> persistRegisteredAnalyses(UUID userId, int count) {
        List<UUID> analysisIds = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            UUID analysisId = UUID.randomUUID();
            Instant createdAt = BASE_TIME.plusSeconds(index);
            analysisRepository.create(Analysis.create(
                    analysisId,
                    new RegisteredUserOwner(userId),
                    PriceTolerance.exactMatch(),
                    createdAt));
            analysisIds.add(analysisId);
        }
        return analysisIds;
    }

    private UUID persistGuestAnalysis(UUID guestSessionId) {
        Instant createdAt = BASE_TIME.plusSeconds(10_000);
        Instant expiresAt = createdAt.plusSeconds(86_400);
        UUID analysisId = UUID.randomUUID();
        analysisRepository.create(Analysis.create(
                analysisId,
                new GuestSessionOwner(guestSessionId, expiresAt),
                PriceTolerance.exactMatch(),
                createdAt));
        return analysisId;
    }

    private UUID persistGuestAnalysisAt(UUID guestSessionId, Instant createdAt) {
        UUID analysisId = UUID.randomUUID();
        Instant expiresAt = createdAt.plusSeconds(86_400);
        analysisRepository.create(Analysis.create(
                analysisId,
                new GuestSessionOwner(guestSessionId, expiresAt),
                PriceTolerance.exactMatch(),
                createdAt));
        return analysisId;
    }

    private UUID persistAnalysisWithLookup(
            UUID userId, String supplierName, String invoiceNumber, String referenceNumber) {
        UUID analysisId = UUID.randomUUID();
        analysisRepository.create(Analysis.create(
                analysisId,
                new RegisteredUserOwner(userId),
                PriceTolerance.exactMatch(),
                BASE_TIME));
        setLookupFields(analysisId, supplierName, invoiceNumber, referenceNumber);
        return analysisId;
    }

    private UUID persistRegisteredAnalysisAt(UUID userId, Instant createdAt) {
        UUID analysisId = UUID.randomUUID();
        analysisRepository.create(Analysis.create(
                analysisId,
                new RegisteredUserOwner(userId),
                PriceTolerance.exactMatch(),
                createdAt));
        return analysisId;
    }

    private void persistSortableAnalysis(
            UUID userId,
            UUID analysisId,
            Instant createdAt,
            BigDecimal difference,
            BigDecimal invoicedTotal,
            String supplierName) {
        analysisRepository.create(Analysis.create(
                analysisId,
                new RegisteredUserOwner(userId),
                PriceTolerance.exactMatch(),
                createdAt));
        jdbcTemplate.update("""
                UPDATE invoward.analyses
                SET difference = ?, invoiced_total = ?, supplier_name = ?
                WHERE id = ?
                """, difference, invoicedTotal, supplierName, analysisId);
    }

    private void setLookupFields(UUID analysisId, String supplierName, String invoiceNumber, String referenceNumber) {
        jdbcTemplate.update(
                "UPDATE invoward.analyses SET supplier_name = ?, invoice_number = ?, reference_number = ? WHERE id = ?",
                supplierName,
                invoiceNumber,
                referenceNumber,
                analysisId);
    }

    private void setDifference(UUID analysisId, BigDecimal difference) {
        jdbcTemplate.update("UPDATE invoward.analyses SET difference = ? WHERE id = ?", difference, analysisId);
    }

    private void insertDiscrepancy(UUID analysisId, String type) {
        jdbcTemplate.update("""
                INSERT INTO invoward.discrepancies
                    (id, analysis_id, type, explanation, resolution_status, created_at, updated_at)
                VALUES (?, ?, ?, 'History filter integration fixture', 'OPEN', ?, ?)
                """, UUID.randomUUID(), analysisId, type,
                Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME));
    }

    private void setHistoryState(
            UUID analysisId,
            AnalysisReconciliationStatus reconciliationStatus,
            AnalysisStatus processingStatus,
            AnalysisReviewStatus reviewStatus,
            String currency) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    UPDATE invoward.analyses
                    SET reconciliation_status = ?, status = ?, review_status = ?, currency = ?
                    WHERE id = ?
                    """);
            statement.setString(1, reconciliationStatus == null ? null : reconciliationStatus.name());
            statement.setString(2, processingStatus.name());
            statement.setString(3, reviewStatus.name());
            statement.setString(4, currency);
            statement.setObject(5, analysisId);
            return statement;
        });
    }

    private static void assertIds(Set<UUID> expected, AnalysisHistoryPage page) {
        assertEquals(expected, Set.copyOf(ids(page)));
        assertEquals(expected.size(), page.totalElements());
    }

    private static List<UUID> ids(AnalysisHistoryPage page) {
        return page.content().stream().map(AnalysisHistoryItem::id).toList();
    }

    private UUID insertUser() throws Exception {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
    }

    private UUID insertGuestSession() throws Exception {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
    }

    private static AnalysisHistoryQuery query(int page, int size) {
        return query(page, size, null);
    }

    private static AnalysisHistoryQuery query(int page, int size, AnalysisHistorySort sort) {
        return AnalysisHistoryQuery.of(
                page, size, null, null, null, null, null, null, null, null, null, null, null, sort);
    }

    private static AnalysisHistoryQuery queryWithDiscrepancy(
            int page,
            int size,
            HistoryDiscrepancyType discrepancyType,
            AnalysisReconciliationStatus reconciliationStatus,
            LocalDate from,
            LocalDate to) {
        return AnalysisHistoryQuery.of(
                page, size, null, reconciliationStatus, null, null, null, discrepancyType,
                null, from, to, null, null, null);
    }

    private static AnalysisHistoryQuery query(String search, String supplier) {
        return AnalysisHistoryQuery.of(
                null, null, search, null, null, null, supplier, null, null, null, null, null, null, null);
    }

    private static AnalysisHistoryQuery query(
            AnalysisReconciliationStatus reconciliationStatus,
            AnalysisStatus processingStatus,
            AnalysisReviewStatus reviewStatus,
            String currency) {
        return AnalysisHistoryQuery.of(
                null, null, null, reconciliationStatus, processingStatus, reviewStatus,
                null, null, currency, null, null, null, null, null);
    }

    private static AnalysisHistoryQuery queryByDateAndMagnitude(
            LocalDate from, LocalDate to, BigDecimal minDifference, BigDecimal maxDifference) {
        return AnalysisHistoryQuery.of(
                null, null, null, null, null, null, null, null, null,
                from, to, minDifference, maxDifference, null);
    }

    private record SortExpectation(AnalysisHistorySort sort, List<UUID> expectedIds) {
    }
}
