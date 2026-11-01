package io.github.guillermodubon.invoward.insights.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisJpaEntity;
import io.github.guillermodubon.invoward.insights.application.model.DashboardRecentQuery;
import io.github.guillermodubon.invoward.insights.application.model.DashboardRecentView;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPageRequest;
import io.github.guillermodubon.invoward.insights.application.port.InsightsRecentAnalysisQueryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL/JPA adapter for paged, registered-owner dashboard analysis reads. */
@Repository
@Transactional(readOnly = true)
public class JpaInsightsRecentAnalysisQueryRepository implements InsightsRecentAnalysisQueryRepository {

    private final ObjectProvider<EntityManager> entityManagerProvider;

    public JpaInsightsRecentAnalysisQueryRepository(ObjectProvider<EntityManager> entityManagerProvider) {
        this.entityManagerProvider = Objects.requireNonNull(
                entityManagerProvider, "entityManagerProvider is required");
    }

    @Override
    public InsightsPage<AnalysisHistoryItem> pageRecent(UUID registeredUserId, DashboardRecentQuery query) {
        Objects.requireNonNull(registeredUserId, "registeredUserId is required");
        Objects.requireNonNull(query, "query is required");

        EntityManager entityManager = entityManagerProvider.getObject();
        InsightsPageRequest pagination = query.pagination();
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();

        CriteriaQuery<AnalysisHistoryItem> pageQuery = criteriaBuilder.createQuery(AnalysisHistoryItem.class);
        Root<AnalysisJpaEntity> analysis = pageQuery.from(AnalysisJpaEntity.class);
        pageQuery.select(historyProjection(criteriaBuilder, analysis));
        pageQuery.where(predicates(criteriaBuilder, analysis, registeredUserId, query.view()));
        pageQuery.orderBy(ordering(criteriaBuilder, analysis, query.view()));

        TypedQuery<AnalysisHistoryItem> typedPageQuery = entityManager.createQuery(pageQuery);
        typedPageQuery.setFirstResult(Math.toIntExact((long) pagination.page() * pagination.size()));
        typedPageQuery.setMaxResults(pagination.size());
        List<AnalysisHistoryItem> content = typedPageQuery.getResultList();

        CriteriaQuery<Long> countQuery = criteriaBuilder.createQuery(Long.class);
        Root<AnalysisJpaEntity> countAnalysis = countQuery.from(AnalysisJpaEntity.class);
        countQuery.select(criteriaBuilder.count(countAnalysis));
        countQuery.where(predicates(criteriaBuilder, countAnalysis, registeredUserId, query.view()));
        long totalElements = entityManager.createQuery(countQuery).getSingleResult();

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

    private static Predicate[] predicates(
            CriteriaBuilder criteriaBuilder,
            Root<AnalysisJpaEntity> analysis,
            UUID registeredUserId,
            DashboardRecentView view) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(criteriaBuilder.equal(analysis.get("userId"), registeredUserId));
        if (view == DashboardRecentView.NEEDS_ATTENTION) {
            predicates.add(criteriaBuilder.equal(analysis.get("status"), AnalysisStatus.COMPLETED));
            predicates.add(criteriaBuilder.equal(
                    analysis.get("reconciliationStatus"), AnalysisReconciliationStatus.REVIEW_REQUIRED));
            predicates.add(criteriaBuilder.equal(analysis.get("reviewStatus"), AnalysisReviewStatus.PENDING));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private static List<Order> ordering(
            CriteriaBuilder criteriaBuilder,
            Root<AnalysisJpaEntity> analysis,
            DashboardRecentView view) {
        if (view == DashboardRecentView.NEEDS_ATTENTION) {
            return List.of(
                    criteriaBuilder.desc(analysis.<Instant>get("completedAt")),
                    criteriaBuilder.desc(analysis.get("id")));
        }
        return List.of(
                criteriaBuilder.desc(analysis.<Instant>get("createdAt")),
                criteriaBuilder.desc(analysis.get("id")));
    }
}
