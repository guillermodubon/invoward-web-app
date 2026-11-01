package io.github.guillermodubon.invoward.insights.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisJpaEntity;
import io.github.guillermodubon.invoward.insights.application.model.DiscrepancyTypeFrequency;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierDisplayNameSelector;
import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAggregateCounts;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAnalysisQuery;
import io.github.guillermodubon.invoward.insights.application.model.SupplierHistorySort;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKeyAggregate;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListQuery;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListSort;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierSummaryQueryRepository;
import io.github.guillermodubon.invoward.insights.application.model.SupplierCurrencyAmounts;
import io.github.guillermodubon.invoward.insights.application.model.SupplierFinancialDataQualityCounts;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierQueryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;

/** PostgreSQL supplier reads and aggregates, scoped to the registered owner in every statement. */
@Repository
@Transactional(readOnly = true)
public class JpaInsightsSupplierQueryRepository implements
        InsightsSupplierQueryRepository {

    private static final int MAX_SUPPLIER_KEY_BATCH = 100;
    private static final int MAX_SUPPLIER_RESULT_LIMIT = 5;

    private static final String SUPPLIER_EXISTS_AND_DISPLAY_NAME_SQL = """
            SELECT COALESCE(latest_name.supplier_name, owned.supplier_key)
            FROM (
                SELECT a.supplier_key
                FROM invoward.analyses a
                WHERE a.user_id = :registeredUserId
                  AND a.supplier_key = :supplierKey
                  AND a.status = 'COMPLETED'
                  AND a.reconciliation_status IN ('MATCHED', 'REVIEW_REQUIRED')
                LIMIT 1
            ) owned
            LEFT JOIN LATERAL (
                SELECT candidate.supplier_name
                FROM invoward.analyses candidate
                WHERE candidate.user_id = :registeredUserId
                  AND candidate.supplier_key = :supplierKey
                  AND candidate.status = 'COMPLETED'
                  AND candidate.reconciliation_status IN ('MATCHED', 'REVIEW_REQUIRED')
                  AND candidate.supplier_name IS NOT NULL
                  AND candidate.supplier_name ~ '[^[:space:]]'
                ORDER BY candidate.completed_at DESC NULLS LAST, candidate.id DESC
                LIMIT 1
            ) latest_name ON TRUE
            """;

    private static final String SUPPLIER_COMPLETED_COUNTS_SQL = """
            SELECT count(*),
                   count(*) FILTER (WHERE a.reconciliation_status = 'MATCHED'),
                   count(*) FILTER (WHERE a.reconciliation_status = 'REVIEW_REQUIRED'),
                   count(*) FILTER (WHERE a.reconciliation_status = 'REVIEW_REQUIRED'
                                    AND a.difference IS NULL),
                   count(*) FILTER (WHERE a.currency IS NULL)
            FROM invoward.analyses a
            WHERE a.user_id = :registeredUserId
              AND a.supplier_key = :supplierKey
              AND a.status = 'COMPLETED'
              AND a.reconciliation_status IN ('MATCHED', 'REVIEW_REQUIRED')
            """;

    private static final String SUPPLIER_COMMON_ISSUES_SQL = """
            SELECT d.type, count(*) AS discrepancy_count
            FROM invoward.discrepancies d
            JOIN invoward.analyses a ON a.id = d.analysis_id
            WHERE a.user_id = :registeredUserId
              AND a.supplier_key = :supplierKey
              AND a.status = 'COMPLETED'
              AND a.reconciliation_status IN ('MATCHED', 'REVIEW_REQUIRED')
            GROUP BY d.type
            ORDER BY count(*) DESC, d.type ASC
            LIMIT :limit
            """;

    private static final String SUPPLIER_PROJECTION_CTE = """
            WITH supplier_group_counts AS (
                SELECT a.supplier_key,
                       count(*) AS completed_analyses,
                       count(*) FILTER (
                           WHERE a.reconciliation_status = 'REVIEW_REQUIRED'
                       ) AS analyses_with_differences
                FROM invoward.analyses a
                WHERE a.user_id = :registeredUserId
                  AND a.status = 'COMPLETED'
                  AND a.reconciliation_status IN ('MATCHED', 'REVIEW_REQUIRED')
                  AND a.supplier_key IS NOT NULL
                  AND a.supplier_key ~ '[^[:space:]]'
                GROUP BY a.supplier_key
            ), supplier_projection AS (
                SELECT grouped.supplier_key,
                       latest_name.supplier_name AS latest_supplier_name,
                       grouped.completed_analyses,
                       grouped.analyses_with_differences
                FROM supplier_group_counts grouped
                LEFT JOIN LATERAL (
                    SELECT candidate.supplier_name
                    FROM invoward.analyses candidate
                    WHERE candidate.user_id = :registeredUserId
                      AND candidate.status = 'COMPLETED'
                      AND candidate.reconciliation_status IN ('MATCHED', 'REVIEW_REQUIRED')
                      AND candidate.supplier_key = grouped.supplier_key
                      AND candidate.supplier_name IS NOT NULL
                      AND candidate.supplier_name ~ '[^[:space:]]'
                    ORDER BY candidate.completed_at DESC NULLS LAST, candidate.id DESC
                    LIMIT 1
                ) latest_name ON TRUE
            )
            """;

    private static final String FILTERED_SUPPLIER_PREDICATE = """
            (:searchEnabled = FALSE
             OR COALESCE(latest_supplier_name, supplier_key) ILIKE :searchPattern ESCAPE '!'
             OR supplier_key ILIKE :searchPattern ESCAPE '!')
            """;

    private static final String PAGE_SQL_TEMPLATE = SUPPLIER_PROJECTION_CTE + """
            SELECT supplier_key,
                   latest_supplier_name,
                   completed_analyses,
                   analyses_with_differences
            FROM supplier_projection
            WHERE %s
            ORDER BY %s, supplier_key ASC
            LIMIT :limit OFFSET :offset
            """;

    private static final String COUNT_SQL = SUPPLIER_PROJECTION_CTE + """
            SELECT count(*)
            FROM supplier_projection
            WHERE """ + FILTERED_SUPPLIER_PREDICATE;

    private static final String SUPPLIER_TOTALS_BY_CURRENCY_SQL = """
            SELECT a.supplier_key, a.currency, count(*), sum(a.invoiced_total)
            FROM invoward.analyses a
            WHERE a.user_id = :registeredUserId
              AND a.status = 'COMPLETED'
              AND a.reconciliation_status IN ('MATCHED', 'REVIEW_REQUIRED')
              AND a.supplier_key IN (:supplierKeys)
              AND a.currency IS NOT NULL
              AND a.invoiced_total IS NOT NULL
            GROUP BY a.supplier_key, a.currency
            ORDER BY a.supplier_key ASC, a.currency ASC
            """;

    private static final String SUPPLIER_POTENTIAL_DIFFERENCES_BY_CURRENCY_SQL = """
            SELECT a.supplier_key, a.currency, count(*), sum(abs(a.difference))
            FROM invoward.analyses a
            WHERE a.user_id = :registeredUserId
              AND a.status = 'COMPLETED'
              AND a.reconciliation_status = 'REVIEW_REQUIRED'
              AND a.supplier_key IN (:supplierKeys)
              AND a.currency IS NOT NULL
              AND a.difference IS NOT NULL
            GROUP BY a.supplier_key, a.currency
            ORDER BY a.supplier_key ASC, a.currency ASC
            """;

    private static final String SUPPLIER_MISSING_FINANCIAL_DATA_SQL = """
            SELECT a.supplier_key,
                   count(*) FILTER (
                       WHERE a.reconciliation_status = 'REVIEW_REQUIRED'
                         AND a.difference IS NULL
                   ),
                   count(*) FILTER (WHERE a.currency IS NULL)
            FROM invoward.analyses a
            WHERE a.user_id = :registeredUserId
              AND a.status = 'COMPLETED'
              AND a.reconciliation_status IN ('MATCHED', 'REVIEW_REQUIRED')
              AND a.supplier_key IN (:supplierKeys)
            GROUP BY a.supplier_key
            ORDER BY a.supplier_key ASC
            """;

    private final ObjectProvider<EntityManager> entityManagerProvider;

    public JpaInsightsSupplierQueryRepository(ObjectProvider<EntityManager> entityManagerProvider) {
        this.entityManagerProvider = Objects.requireNonNull(
                entityManagerProvider, "entityManagerProvider is required");
    }

    @Override
    public InsightsPage<SupplierKeyAggregate> pageSupplierKeys(
            UUID registeredUserId, SupplierListQuery query) {
        Objects.requireNonNull(registeredUserId, "registeredUserId is required");
        Objects.requireNonNull(query, "query is required");

        EntityManager entityManager = entityManagerProvider.getObject();
        SearchFilter searchFilter = SearchFilter.from(query.search());
        long totalElements = asLong(
                filteredQuery(entityManager, COUNT_SQL, registeredUserId, searchFilter).getSingleResult());

        SupplierListSort sort = query.sort();
        String pageSql = PAGE_SQL_TEMPLATE.formatted(FILTERED_SUPPLIER_PREDICATE, orderBy(sort));
        long offset = (long) query.pagination().page() * query.pagination().size();
        Query pageQuery = filteredQuery(entityManager, pageSql, registeredUserId, searchFilter)
                .setParameter("limit", query.pagination().size())
                .setParameter("offset", Math.toIntExact(offset));

        List<SupplierKeyAggregate> content = new ArrayList<>();
        for (Object result : pageQuery.getResultList()) {
            Object[] row = (Object[]) result;
            SupplierKey key = new SupplierKey((String) row[0]);
            content.add(new SupplierKeyAggregate(
                    key,
                    SupplierDisplayNameSelector.select(key, (String) row[1]),
                    asLong(row[2]),
                    asLong(row[3])));
        }

        return new InsightsPage<>(
                content, query.pagination().page(), query.pagination().size(), totalElements);
    }

    @Override
    public Optional<String> findOwnedCompletedSupplierDisplayName(
            UUID registeredUserId, SupplierKey supplierKey) {
        Objects.requireNonNull(registeredUserId, "registeredUserId is required");
        Objects.requireNonNull(supplierKey, "supplierKey is required");
        List<?> result = entityManagerProvider.getObject()
                .createNativeQuery(SUPPLIER_EXISTS_AND_DISPLAY_NAME_SQL)
                .setParameter("registeredUserId", registeredUserId)
                .setParameter("supplierKey", supplierKey.value())
                .setMaxResults(1)
                .getResultList();
        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of((String) result.getFirst());
    }

    @Override
    public SupplierAggregateCounts fetchSupplierCompletedCounts(
            UUID registeredUserId, SupplierKey supplierKey) {
        Objects.requireNonNull(registeredUserId, "registeredUserId is required");
        Objects.requireNonNull(supplierKey, "supplierKey is required");
        Object[] row = (Object[]) entityManagerProvider.getObject()
                .createNativeQuery(SUPPLIER_COMPLETED_COUNTS_SQL)
                .setParameter("registeredUserId", registeredUserId)
                .setParameter("supplierKey", supplierKey.value())
                .getSingleResult();
        return new SupplierAggregateCounts(
                asLong(row[0]), asLong(row[1]), asLong(row[2]), asLong(row[3]), asLong(row[4]));
    }

    @Override
    public List<DiscrepancyTypeFrequency> fetchSupplierCommonIssues(
            UUID registeredUserId, SupplierKey supplierKey, int limit) {
        Objects.requireNonNull(registeredUserId, "registeredUserId is required");
        Objects.requireNonNull(supplierKey, "supplierKey is required");
        requireSupplierResultLimit(limit);
        Query query = entityManagerProvider.getObject()
                .createNativeQuery(SUPPLIER_COMMON_ISSUES_SQL)
                .setParameter("registeredUserId", registeredUserId)
                .setParameter("supplierKey", supplierKey.value())
                .setParameter("limit", limit);
        List<DiscrepancyTypeFrequency> frequencies = new ArrayList<>();
        for (Object result : query.getResultList()) {
            Object[] row = (Object[]) result;
            frequencies.add(new DiscrepancyTypeFrequency(
                    DiscrepancyType.valueOf((String) row[0]), asLong(row[1])));
        }
        return List.copyOf(frequencies);
    }

    @Override
    public List<AnalysisHistoryItem> fetchSupplierRecent(
            UUID registeredUserId, SupplierKey supplierKey, int limit) {
        Objects.requireNonNull(registeredUserId, "registeredUserId is required");
        Objects.requireNonNull(supplierKey, "supplierKey is required");
        requireSupplierResultLimit(limit);

        EntityManager entityManager = entityManagerProvider.getObject();
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<AnalysisHistoryItem> criteriaQuery = criteriaBuilder.createQuery(AnalysisHistoryItem.class);
        Root<AnalysisJpaEntity> analysis = criteriaQuery.from(AnalysisJpaEntity.class);
        criteriaQuery.select(criteriaBuilder.construct(
                AnalysisHistoryItem.class,
                analysis.get("id"),
                analysis.get("status"),
                analysis.get("reconciliationStatus"),
                analysis.get("reviewStatus"),
                analysis.get("reviewedAt"),
                analysis.get("supplierName"),
                analysis.get("referenceType"),
                analysis.get("referenceNumber"),
                analysis.get("invoiceNumber"),
                analysis.get("currency"),
                analysis.get("referenceTotal"),
                analysis.get("invoicedTotal"),
                analysis.get("difference"),
                analysis.get("createdAt"),
                analysis.get("updatedAt"),
                analysis.get("completedAt")));
        Predicate completedWithReconciliation = analysis.get("reconciliationStatus").in(
                AnalysisReconciliationStatus.MATCHED, AnalysisReconciliationStatus.REVIEW_REQUIRED);
        criteriaQuery.where(
                criteriaBuilder.equal(analysis.get("userId"), registeredUserId),
                criteriaBuilder.equal(analysis.get("supplierKey"), supplierKey.value()),
                criteriaBuilder.equal(analysis.get("status"), AnalysisStatus.COMPLETED),
                completedWithReconciliation);
        criteriaQuery.orderBy(
                criteriaBuilder.asc(criteriaBuilder.<Integer>selectCase()
                        .when(criteriaBuilder.isNull(analysis.get("completedAt")), 1)
                        .otherwise(0)),
                criteriaBuilder.desc(analysis.get("completedAt")),
                criteriaBuilder.desc(analysis.get("id")));

        TypedQuery<AnalysisHistoryItem> typedQuery = entityManager.createQuery(criteriaQuery);
        typedQuery.setMaxResults(limit);
        return List.copyOf(typedQuery.getResultList());
    }

    @Override
    public InsightsPage<AnalysisHistoryItem> pageSupplierAnalyses(
            UUID registeredUserId,
            SupplierKey supplierKey,
            SupplierAnalysisQuery query) {
        Objects.requireNonNull(registeredUserId, "registeredUserId is required");
        Objects.requireNonNull(supplierKey, "supplierKey is required");
        Objects.requireNonNull(query, "query is required");

        EntityManager entityManager = entityManagerProvider.getObject();
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        var pagination = query.pagination();

        CriteriaQuery<AnalysisHistoryItem> pageCriteria =
                criteriaBuilder.createQuery(AnalysisHistoryItem.class);
        Root<AnalysisJpaEntity> analysis = pageCriteria.from(AnalysisJpaEntity.class);
        pageCriteria.select(historyProjection(criteriaBuilder, analysis));
        pageCriteria.where(supplierAnalysisPredicates(
                criteriaBuilder, analysis, registeredUserId, supplierKey, query));
        pageCriteria.orderBy(supplierAnalysisOrdering(criteriaBuilder, analysis, query.sort()));

        TypedQuery<AnalysisHistoryItem> pageQuery = entityManager.createQuery(pageCriteria);
        pageQuery.setFirstResult(Math.toIntExact((long) pagination.page() * pagination.size()));
        pageQuery.setMaxResults(pagination.size());
        List<AnalysisHistoryItem> content = pageQuery.getResultList();

        CriteriaQuery<Long> countCriteria = criteriaBuilder.createQuery(Long.class);
        Root<AnalysisJpaEntity> countAnalysis = countCriteria.from(AnalysisJpaEntity.class);
        countCriteria.select(criteriaBuilder.count(countAnalysis));
        countCriteria.where(supplierAnalysisPredicates(
                criteriaBuilder, countAnalysis, registeredUserId, supplierKey, query));
        long totalElements = entityManager.createQuery(countCriteria).getSingleResult();

        return new InsightsPage<>(content, pagination.page(), pagination.size(), totalElements);
    }

    private static jakarta.persistence.criteria.CompoundSelection<AnalysisHistoryItem> historyProjection(
            CriteriaBuilder criteriaBuilder, Root<AnalysisJpaEntity> analysis) {
        return criteriaBuilder.construct(
                AnalysisHistoryItem.class,
                analysis.get("id"),
                analysis.get("status"),
                analysis.get("reconciliationStatus"),
                analysis.get("reviewStatus"),
                analysis.get("reviewedAt"),
                analysis.get("supplierName"),
                analysis.get("referenceType"),
                analysis.get("referenceNumber"),
                analysis.get("invoiceNumber"),
                analysis.get("currency"),
                analysis.get("referenceTotal"),
                analysis.get("invoicedTotal"),
                analysis.get("difference"),
                analysis.get("createdAt"),
                analysis.get("updatedAt"),
                analysis.get("completedAt"));
    }

    private static Predicate[] supplierAnalysisPredicates(
            CriteriaBuilder criteriaBuilder,
            Root<AnalysisJpaEntity> analysis,
            UUID registeredUserId,
            SupplierKey supplierKey,
            SupplierAnalysisQuery query) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(criteriaBuilder.equal(analysis.get("userId"), registeredUserId));
        predicates.add(criteriaBuilder.equal(analysis.get("supplierKey"), supplierKey.value()));
        if (query.processingStatus() != null) {
            predicates.add(criteriaBuilder.equal(analysis.get("status"), query.processingStatus()));
        }
        if (query.reviewStatus() != null) {
            predicates.add(criteriaBuilder.equal(analysis.get("reviewStatus"), query.reviewStatus()));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private static List<Order> supplierAnalysisOrdering(
            CriteriaBuilder criteriaBuilder,
            Root<AnalysisJpaEntity> analysis,
            SupplierHistorySort sort) {
        if (sort.field() == SupplierHistorySort.Field.CREATED_AT) {
            Order createdAt = sort.direction() == SupplierHistorySort.Direction.ASC
                    ? criteriaBuilder.asc(analysis.get("createdAt"))
                    : criteriaBuilder.desc(analysis.get("createdAt"));
            return List.of(createdAt, criteriaBuilder.desc(analysis.get("id")));
        }

        Order nullsLast = criteriaBuilder.asc(criteriaBuilder.<Integer>selectCase()
                .when(criteriaBuilder.isNull(analysis.get("completedAt")), 1)
                .otherwise(0));
        Order completedAt = sort.direction() == SupplierHistorySort.Direction.ASC
                ? criteriaBuilder.asc(analysis.get("completedAt"))
                : criteriaBuilder.desc(analysis.get("completedAt"));
        return List.of(nullsLast, completedAt, criteriaBuilder.desc(analysis.get("id")));
    }

    @Override
    public List<SupplierCurrencyAmounts> fetchSupplierCurrencyAmounts(
            UUID registeredUserId, List<SupplierKey> supplierKeys) {
        return fetchSupplierCurrencyAmounts(
                registeredUserId, supplierKeys, SUPPLIER_TOTALS_BY_CURRENCY_SQL);
    }

    @Override
    public List<SupplierCurrencyAmounts> fetchSupplierPotentialDifferencesByCurrency(
            UUID registeredUserId, List<SupplierKey> supplierKeys) {
        return fetchSupplierCurrencyAmounts(
                registeredUserId, supplierKeys, SUPPLIER_POTENTIAL_DIFFERENCES_BY_CURRENCY_SQL);
    }

    @Override
    public List<SupplierFinancialDataQualityCounts> fetchSupplierMissingFinancialDataCounts(
            UUID registeredUserId, List<SupplierKey> supplierKeys) {
        List<String> keys = validatedBatch(registeredUserId, supplierKeys);
        if (keys.isEmpty()) {
            return List.of();
        }

        Query query = entityManagerProvider.getObject()
                .createNativeQuery(SUPPLIER_MISSING_FINANCIAL_DATA_SQL)
                .setParameter("registeredUserId", registeredUserId)
                .setParameter("supplierKeys", keys);
        List<SupplierFinancialDataQualityCounts> counts = new ArrayList<>();
        for (Object result : query.getResultList()) {
            Object[] row = (Object[]) result;
            counts.add(new SupplierFinancialDataQualityCounts(
                    new SupplierKey((String) row[0]), asLong(row[1]), asLong(row[2])));
        }
        return List.copyOf(counts);
    }

    private List<SupplierCurrencyAmounts> fetchSupplierCurrencyAmounts(
            UUID registeredUserId, List<SupplierKey> supplierKeys, String sql) {
        List<String> keys = validatedBatch(registeredUserId, supplierKeys);
        if (keys.isEmpty()) {
            return List.of();
        }

        Query query = entityManagerProvider.getObject()
                .createNativeQuery(sql)
                .setParameter("registeredUserId", registeredUserId)
                .setParameter("supplierKeys", keys);
        Map<SupplierKey, List<CurrencyAmount>> grouped = new LinkedHashMap<>();
        for (Object result : query.getResultList()) {
            Object[] row = (Object[]) result;
            SupplierKey supplierKey = new SupplierKey((String) row[0]);
            if (!(row[3] instanceof java.math.BigDecimal amount)) {
                throw new IllegalStateException("Supplier currency aggregate amount must be numeric.");
            }
            grouped.computeIfAbsent(supplierKey, ignored -> new ArrayList<>())
                    .add(new CurrencyAmount((String) row[1], amount, asLong(row[2])));
        }

        return grouped.entrySet().stream()
                .map(entry -> new SupplierCurrencyAmounts(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<String> validatedBatch(UUID registeredUserId, List<SupplierKey> supplierKeys) {
        Objects.requireNonNull(registeredUserId, "registeredUserId is required");
        Objects.requireNonNull(supplierKeys, "supplierKeys is required");
        if (supplierKeys.size() > MAX_SUPPLIER_KEY_BATCH) {
            throw new IllegalArgumentException("Supplier key batch exceeds the supported page size.");
        }
        List<SupplierKey> keys = List.copyOf(supplierKeys);
        if (keys.stream().distinct().count() != keys.size()) {
            throw new IllegalArgumentException("Supplier key batch cannot contain duplicates.");
        }
        return keys.stream().map(SupplierKey::value).toList();
    }

    private static Query filteredQuery(
            EntityManager entityManager,
            String sql,
            UUID registeredUserId,
            SearchFilter searchFilter) {
        return entityManager.createNativeQuery(sql)
                .setParameter("registeredUserId", registeredUserId)
                .setParameter("searchEnabled", searchFilter.enabled())
                .setParameter("searchPattern", searchFilter.pattern());
    }

    private static String orderBy(SupplierListSort sort) {
        return switch (sort) {
            case ANALYSIS_COUNT_DESC -> "completed_analyses DESC";
            case ANALYSIS_COUNT_ASC -> "completed_analyses ASC";
            case SUPPLIER_NAME_ASC -> "COALESCE(latest_supplier_name, supplier_key) ASC";
            case SUPPLIER_NAME_DESC -> "COALESCE(latest_supplier_name, supplier_key) DESC";
        };
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Supplier query returned a non-numeric aggregate.");
    }

    private static void requireSupplierResultLimit(int limit) {
        if (limit < 1 || limit > MAX_SUPPLIER_RESULT_LIMIT) {
            throw new IllegalArgumentException("Supplier query limit is outside the supported range.");
        }
    }

    private record SearchFilter(boolean enabled, String pattern) {

        private static SearchFilter from(String value) {
            if (value == null) {
                return new SearchFilter(false, "%");
            }
            String escaped = value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
            return new SearchFilter(true, "%" + escaped + "%");
        }
    }
}
