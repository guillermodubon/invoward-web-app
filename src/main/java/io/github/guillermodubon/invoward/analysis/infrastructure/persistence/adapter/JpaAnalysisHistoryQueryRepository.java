package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.exception.InvalidAnalysisHistoryQueryException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryPage;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryQuery;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistorySort;
import io.github.guillermodubon.invoward.analysis.application.model.HistoryDiscrepancyType;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisHistoryQueryRepository;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL/JPA adapter for bounded registered-user Analysis history reads. */
@Repository
@Transactional(readOnly = true)
public class JpaAnalysisHistoryQueryRepository implements AnalysisHistoryQueryRepository {

    private static final char LIKE_ESCAPE = '\\';
    private static final String DISCREPANCY_ENTITY_NAME = "DiscrepancyJpaEntity";
    private static final String DISCREPANCY_ANALYSIS_ID_ATTRIBUTE = "analysisId";
    private static final String DISCREPANCY_TYPE_ATTRIBUTE = "type";

    private final ObjectProvider<EntityManager> entityManagerProvider;

    public JpaAnalysisHistoryQueryRepository(ObjectProvider<EntityManager> entityManagerProvider) {
        this.entityManagerProvider = Objects.requireNonNull(
                entityManagerProvider, "entityManagerProvider must not be null");
    }

    @Override
    public AnalysisHistoryPage findForRegisteredUser(UUID registeredUserId, AnalysisHistoryQuery query) {
        Objects.requireNonNull(registeredUserId, "registeredUserId must not be null");
        Objects.requireNonNull(query, "query must not be null");

        EntityManager entityManager = entityManagerProvider.getObject();
        Pageable pageable = PageRequest.of(query.page(), query.size());
        List<AnalysisHistoryItem> content = findPageContent(entityManager, registeredUserId, query, pageable);
        long totalElements = countForRegisteredUser(entityManager, registeredUserId, query);

        return new AnalysisHistoryPage(content, pageable.getPageNumber(), pageable.getPageSize(), totalElements);
    }

    private List<AnalysisHistoryItem> findPageContent(
            EntityManager entityManager,
            UUID registeredUserId,
            AnalysisHistoryQuery query,
            Pageable pageable) {
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
        PredicateBindings predicateBindings = createPredicates(
                entityManager, criteriaBuilder, criteriaQuery, analysis, registeredUserId, query);
        criteriaQuery.where(predicateBindings.predicate());
        criteriaQuery.orderBy(historySortOrders(criteriaBuilder, analysis, query.sort()));

        TypedQuery<AnalysisHistoryItem> typedQuery = entityManager.createQuery(criteriaQuery);
        bindParameters(typedQuery, predicateBindings.parameters());
        typedQuery.setFirstResult(Math.toIntExact(pageable.getOffset()));
        typedQuery.setMaxResults(pageable.getPageSize());
        return typedQuery.getResultList();
    }

    private List<Order> historySortOrders(
            CriteriaBuilder criteriaBuilder,
            Root<AnalysisJpaEntity> analysis,
            AnalysisHistorySort sort) {
        Expression<?> primarySort = switch (sort.field()) {
            case CREATED_AT -> analysis.get("createdAt");
            case DIFFERENCE -> criteriaBuilder.abs(analysis.<BigDecimal>get("difference"));
            case INVOICED_TOTAL -> analysis.get("invoicedTotal");
            case SUPPLIER_NAME -> criteriaBuilder.lower(analysis.<String>get("supplierName"));
        };

        CriteriaBuilder.Case<Integer> nullRank = criteriaBuilder.selectCase();
        nullRank.when(criteriaBuilder.isNull(primarySort), 1);

        List<Order> orders = new ArrayList<>();
        orders.add(criteriaBuilder.asc(nullRank.otherwise(0)));
        orders.add(sort.direction() == AnalysisHistorySort.Direction.ASC
                ? criteriaBuilder.asc(primarySort)
                : criteriaBuilder.desc(primarySort));
        if (sort.field() != AnalysisHistorySort.Field.CREATED_AT) {
            orders.add(criteriaBuilder.desc(analysis.get("createdAt")));
        }
        orders.add(criteriaBuilder.desc(analysis.get("id")));
        return orders;
    }

    private long countForRegisteredUser(
            EntityManager entityManager, UUID registeredUserId, AnalysisHistoryQuery query) {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> countQuery = criteriaBuilder.createQuery(Long.class);
        Root<AnalysisJpaEntity> analysis = countQuery.from(AnalysisJpaEntity.class);

        countQuery.select(criteriaBuilder.count(analysis));
        PredicateBindings predicateBindings = createPredicates(
                entityManager, criteriaBuilder, countQuery, analysis, registeredUserId, query);
        countQuery.where(predicateBindings.predicate());

        TypedQuery<Long> typedQuery = entityManager.createQuery(countQuery);
        bindParameters(typedQuery, predicateBindings.parameters());
        return typedQuery.getSingleResult();
    }

    private PredicateBindings createPredicates(
            EntityManager entityManager,
            CriteriaBuilder criteriaBuilder,
            CriteriaQuery<?> criteriaQuery,
            Root<AnalysisJpaEntity> analysis,
            UUID registeredUserId,
            AnalysisHistoryQuery query) {
        List<Predicate> predicates = new ArrayList<>();
        Map<String, Object> parameters = new LinkedHashMap<>();
        predicates.add(criteriaBuilder.equal(analysis.get("userId"), registeredUserId));

        if (query.status() != null) {
            predicates.add(criteriaBuilder.equal(analysis.get("reconciliationStatus"), query.status()));
        }
        if (query.processingStatus() != null) {
            predicates.add(criteriaBuilder.equal(analysis.get("status"), query.processingStatus()));
        }
        if (query.reviewStatus() != null) {
            predicates.add(criteriaBuilder.equal(analysis.get("reviewStatus"), query.reviewStatus()));
        }
        if (query.currency() != null) {
            predicates.add(criteriaBuilder.equal(analysis.get("currency"), query.currency()));
        }
        if (query.discrepancyType() != null) {
            EntityType<?> discrepancyEntity = entityManager.getMetamodel().entity(DISCREPANCY_ENTITY_NAME);
            Subquery<Integer> discrepancyExists = criteriaQuery.subquery(Integer.class);
            Root<?> discrepancy = discrepancyExists.from(discrepancyEntity);
            Path<?> discrepancyType = discrepancy.get(DISCREPANCY_TYPE_ATTRIBUTE);
            ParameterExpression<?> discrepancyTypeParameter = criteriaBuilder.parameter(
                    discrepancyType.getJavaType(), "discrepancyType");

            discrepancyExists.select(criteriaBuilder.literal(1));
            discrepancyExists.where(criteriaBuilder.and(
                    criteriaBuilder.equal(
                            discrepancy.get(DISCREPANCY_ANALYSIS_ID_ATTRIBUTE), analysis.get("id")),
                    criteriaBuilder.equal(discrepancyType, discrepancyTypeParameter)));
            predicates.add(criteriaBuilder.exists(discrepancyExists));
            parameters.put("discrepancyType", matchingPersistenceEnum(
                    discrepancyType.getJavaType(), query.discrepancyType()));
        }

        if (query.from() != null) {
            ParameterExpression<Instant> startInclusive =
                    criteriaBuilder.parameter(Instant.class, "createdAtFrom");
            predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                    analysis.<Instant>get("createdAt"), startInclusive));
            parameters.put("createdAtFrom", startOfUtcDay(query.from()));
        }

        if (query.to() != null) {
            ParameterExpression<Instant> endExclusive =
                    criteriaBuilder.parameter(Instant.class, "createdAtBefore");
            predicates.add(criteriaBuilder.lessThan(
                    analysis.<Instant>get("createdAt"), endExclusive));
            parameters.put("createdAtBefore", startOfUtcDayAfter(query.to()));
        }

        Expression<BigDecimal> absoluteDifference = criteriaBuilder.abs(analysis.<BigDecimal>get("difference"));
        if (query.minDifference() != null) {
            ParameterExpression<BigDecimal> minimum =
                    criteriaBuilder.parameter(BigDecimal.class, "minDifference");
            predicates.add(criteriaBuilder.greaterThanOrEqualTo(absoluteDifference, minimum));
            parameters.put("minDifference", query.minDifference());
        }
        if (query.maxDifference() != null) {
            ParameterExpression<BigDecimal> maximum =
                    criteriaBuilder.parameter(BigDecimal.class, "maxDifference");
            predicates.add(criteriaBuilder.lessThanOrEqualTo(absoluteDifference, maximum));
            parameters.put("maxDifference", query.maxDifference());
        }

        if (query.search() != null) {
            ParameterExpression<String> searchPattern =
                    criteriaBuilder.parameter(String.class, "searchPattern");
            predicates.add(criteriaBuilder.or(
                    criteriaBuilder.like(
                            criteriaBuilder.lower(analysis.get("supplierName")), searchPattern, LIKE_ESCAPE),
                    criteriaBuilder.like(
                            criteriaBuilder.lower(analysis.get("invoiceNumber")), searchPattern, LIKE_ESCAPE),
                    criteriaBuilder.like(
                            criteriaBuilder.lower(analysis.get("referenceNumber")), searchPattern, LIKE_ESCAPE)));
            parameters.put("searchPattern", likePattern(query.search()));
        }

        if (query.supplier() != null) {
            ParameterExpression<String> supplierPattern =
                    criteriaBuilder.parameter(String.class, "supplierPattern");
            predicates.add(criteriaBuilder.like(
                    criteriaBuilder.lower(analysis.get("supplierName")), supplierPattern, LIKE_ESCAPE));
            parameters.put("supplierPattern", likePattern(query.supplier()));
        }

        return new PredicateBindings(criteriaBuilder.and(predicates.toArray(Predicate[]::new)), parameters);
    }

    private Object matchingPersistenceEnum(Class<?> persistenceType, HistoryDiscrepancyType filterType) {
        if (!persistenceType.isEnum()) {
            throw new IllegalStateException("The discrepancy type persistence attribute must be an enum");
        }

        for (Object constant : persistenceType.getEnumConstants()) {
            if (constant instanceof Enum<?> enumConstant && enumConstant.name().equals(filterType.name())) {
                return constant;
            }
        }
        throw new IllegalStateException("The history discrepancy filter is not supported by persistence");
    }

    private String likePattern(String value) {
        String escaped = value.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private Instant startOfUtcDay(LocalDate date) {
        try {
            return date.atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeException exception) {
            throw new InvalidAnalysisHistoryQueryException();
        }
    }

    private Instant startOfUtcDayAfter(LocalDate inclusiveDate) {
        try {
            return startOfUtcDay(inclusiveDate.plusDays(1));
        } catch (DateTimeException exception) {
            throw new InvalidAnalysisHistoryQueryException();
        }
    }

    private <T> void bindParameters(TypedQuery<T> typedQuery, Map<String, Object> parameters) {
        parameters.forEach(typedQuery::setParameter);
    }

    private record PredicateBindings(Predicate predicate, Map<String, Object> parameters) {
    }
}
