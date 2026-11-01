package io.github.guillermodubon.invoward.insights.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.DashboardAggregateCounts;
import io.github.guillermodubon.invoward.insights.application.model.DashboardDataQualityCounts;
import io.github.guillermodubon.invoward.insights.application.model.DashboardTopSupplier;
import io.github.guillermodubon.invoward.insights.application.model.DiscrepancyTypeFrequency;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPeriod;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.model.SupplierDisplayNameSelector;
import io.github.guillermodubon.invoward.insights.application.port.InsightsDashboardQueryRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL aggregate adapter for the registered-user dashboard. */
@Repository
@Transactional(readOnly = true)
public class JpaInsightsDashboardQueryRepository implements InsightsDashboardQueryRepository {

    private static final int MAX_DASHBOARD_LIMIT = 5;

    private static final String COMPLETED_PREDICATE = """
            a.user_id = :registeredUserId
              AND a.status = 'COMPLETED'
              AND a.reconciliation_status IN ('MATCHED', 'REVIEW_REQUIRED')
              AND a.completed_at >= :startInclusive
              AND a.completed_at < :endExclusive
            """;

    private static final String COMPLETED_COUNTS_SQL = """
            SELECT count(*),
                   count(*) FILTER (WHERE a.reconciliation_status = 'MATCHED'),
                   count(*) FILTER (WHERE a.reconciliation_status = 'REVIEW_REQUIRED'),
                   count(*) FILTER (WHERE a.reconciliation_status = 'REVIEW_REQUIRED'
                                    AND a.review_status = 'PENDING')
            FROM invoward.analyses a
            WHERE %s
            """.formatted(COMPLETED_PREDICATE);

    private static final String FAILED_COUNT_SQL = """
            SELECT count(*)
            FROM invoward.analyses a
            WHERE a.user_id = :registeredUserId
              AND a.status = 'FAILED'
              AND a.created_at >= :startInclusive
              AND a.created_at < :endExclusive
            """;

    private static final String TOTALS_BY_CURRENCY_SQL = """
            SELECT a.currency, count(*), sum(a.invoiced_total)
            FROM invoward.analyses a
            WHERE %s
              AND a.currency IS NOT NULL
              AND a.invoiced_total IS NOT NULL
            GROUP BY a.currency
            ORDER BY a.currency ASC
            """.formatted(COMPLETED_PREDICATE);

    private static final String POTENTIAL_DIFFERENCES_BY_CURRENCY_SQL = """
            SELECT a.currency, count(*), sum(abs(a.difference))
            FROM invoward.analyses a
            WHERE %s
              AND a.reconciliation_status = 'REVIEW_REQUIRED'
              AND a.currency IS NOT NULL
              AND a.difference IS NOT NULL
            GROUP BY a.currency
            ORDER BY a.currency ASC
            """.formatted(COMPLETED_PREDICATE);

    private static final String TOP_SUPPLIERS_SQL = """
            SELECT grouped.supplier_key,
                   latest_name.supplier_name,
                   grouped.completed_analyses
            FROM (
                SELECT a.supplier_key, count(*) AS completed_analyses
                FROM invoward.analyses a
                WHERE %s
                  AND a.supplier_key IS NOT NULL
                  AND btrim(a.supplier_key) <> ''
                GROUP BY a.supplier_key
                ORDER BY count(*) DESC, a.supplier_key ASC
                LIMIT :limit
            ) grouped
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
            ORDER BY grouped.completed_analyses DESC, grouped.supplier_key ASC
            """.formatted(COMPLETED_PREDICATE);

    private static final String COMMON_DISCREPANCIES_SQL = """
            SELECT d.type, count(*) AS discrepancy_count
            FROM invoward.discrepancies d
            JOIN invoward.analyses a ON a.id = d.analysis_id
            WHERE %s
            GROUP BY d.type
            ORDER BY count(*) DESC, d.type ASC
            LIMIT :limit
            """.formatted(COMPLETED_PREDICATE);

    private static final String MISSING_DATA_COUNTS_SQL = """
            SELECT count(*) FILTER (WHERE a.reconciliation_status = 'REVIEW_REQUIRED'
                                    AND a.difference IS NULL),
                   count(*) FILTER (WHERE a.currency IS NULL),
                   count(*) FILTER (WHERE a.supplier_key IS NULL OR btrim(a.supplier_key) = '')
            FROM invoward.analyses a
            WHERE %s
            """.formatted(COMPLETED_PREDICATE);

    private final ObjectProvider<EntityManager> entityManagerProvider;

    public JpaInsightsDashboardQueryRepository(ObjectProvider<EntityManager> entityManagerProvider) {
        this.entityManagerProvider = Objects.requireNonNull(
                entityManagerProvider, "entityManagerProvider is required");
    }

    @Override
    public DashboardAggregateCounts fetchCompletedCounts(UUID registeredUserId, InsightsPeriod completedPeriod) {
        Query query = periodQuery(COMPLETED_COUNTS_SQL, registeredUserId, completedPeriod);
        Object[] row = (Object[]) query.getSingleResult();
        return new DashboardAggregateCounts(
                asLong(row[0]), asLong(row[1]), asLong(row[2]), asLong(row[3]));
    }

    @Override
    public long fetchFailedCountByCreationPeriod(UUID registeredUserId, InsightsPeriod creationPeriod) {
        Query query = periodQuery(FAILED_COUNT_SQL, registeredUserId, creationPeriod);
        return asLong(query.getSingleResult());
    }

    @Override
    public List<CurrencyAmount> fetchTotalsByCurrency(UUID registeredUserId, InsightsPeriod completedPeriod) {
        return currencyAmounts(periodQuery(TOTALS_BY_CURRENCY_SQL, registeredUserId, completedPeriod));
    }

    @Override
    public List<CurrencyAmount> fetchPotentialDifferencesByCurrency(
            UUID registeredUserId, InsightsPeriod completedPeriod) {
        return currencyAmounts(periodQuery(
                POTENTIAL_DIFFERENCES_BY_CURRENCY_SQL, registeredUserId, completedPeriod));
    }

    @Override
    public List<DashboardTopSupplier> fetchTopSuppliers(
            UUID registeredUserId, InsightsPeriod completedPeriod, int limit) {
        requireLimit(limit);
        Query query = periodQuery(TOP_SUPPLIERS_SQL, registeredUserId, completedPeriod)
                .setParameter("limit", limit);
        List<DashboardTopSupplier> suppliers = new ArrayList<>();
        for (Object result : query.getResultList()) {
            Object[] row = (Object[]) result;
            SupplierKey supplierKey = new SupplierKey((String) row[0]);
            suppliers.add(new DashboardTopSupplier(
                    supplierKey,
                    SupplierDisplayNameSelector.select(supplierKey, (String) row[1]),
                    asLong(row[2])));
        }
        return List.copyOf(suppliers);
    }

    @Override
    public List<DiscrepancyTypeFrequency> fetchCommonDiscrepancyTypes(
            UUID registeredUserId, InsightsPeriod completedPeriod, int limit) {
        requireLimit(limit);
        Query query = periodQuery(COMMON_DISCREPANCIES_SQL, registeredUserId, completedPeriod)
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
    public DashboardDataQualityCounts fetchMissingDataCounts(
            UUID registeredUserId, InsightsPeriod completedPeriod) {
        Object[] row = (Object[]) periodQuery(
                MISSING_DATA_COUNTS_SQL, registeredUserId, completedPeriod).getSingleResult();
        return new DashboardDataQualityCounts(asLong(row[0]), asLong(row[1]), asLong(row[2]));
    }

    private Query periodQuery(String sql, UUID registeredUserId, InsightsPeriod period) {
        Objects.requireNonNull(registeredUserId, "registeredUserId is required");
        Objects.requireNonNull(period, "period is required");
        Instant startInclusive = period.fromInclusive().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endExclusive = period.toExclusive().atStartOfDay(ZoneOffset.UTC).toInstant();
        return entityManagerProvider.getObject()
                .createNativeQuery(sql)
                .setParameter("registeredUserId", registeredUserId)
                .setParameter("startInclusive", startInclusive)
                .setParameter("endExclusive", endExclusive);
    }

    private List<CurrencyAmount> currencyAmounts(Query query) {
        List<CurrencyAmount> amounts = new ArrayList<>();
        for (Object result : query.getResultList()) {
            Object[] row = (Object[]) result;
            if (!(row[2] instanceof BigDecimal amount)) {
                throw new IllegalStateException("Currency aggregate amount must be numeric.");
            }
            amounts.add(new CurrencyAmount((String) row[0], amount, asLong(row[1])));
        }
        return List.copyOf(amounts);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Database aggregate count must be numeric.");
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_DASHBOARD_LIMIT) {
            throw new IllegalArgumentException("Dashboard aggregate limit is outside the supported range.");
        }
    }
}
