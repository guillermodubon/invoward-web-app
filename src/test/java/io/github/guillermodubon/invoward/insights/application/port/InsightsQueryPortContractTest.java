package io.github.guillermodubon.invoward.insights.application.port;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsQueryPortContractTest {

    private static final List<Class<?>> PORTS = List.of(
            InsightsDashboardQueryRepository.class,
            InsightsRecentAnalysisQueryRepository.class,
            InsightsSupplierQueryRepository.class,
            InsightsSupplierListQueryRepository.class,
            InsightsSupplierFinancialQueryRepository.class,
            InsightsSupplierSummaryQueryRepository.class);

    @Test
    void queryPortsAreInterfacesAndEveryQueryReceivesRegisteredOwnerIdFirst() {
        for (Class<?> port : PORTS) {
            assertTrue(port.isInterface(), port.getSimpleName() + " must remain an application port");
            for (Method method : port.getDeclaredMethods()) {
                assertEquals(UUID.class, method.getParameterTypes()[0],
                        port.getSimpleName() + "." + method.getName() + " must be owner-scoped");
            }
        }
    }

    @Test
    void queryPortsDoNotExposeSpringDataPersistenceOrWebTypes() {
        for (Class<?> port : PORTS) {
            for (Method method : port.getDeclaredMethods()) {
                assertProviderNeutral(method.getReturnType());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertProviderNeutral(parameterType);
                }
            }
        }
    }

    private static void assertProviderNeutral(Class<?> type) {
        String name = type.getName();
        assertTrue(!name.startsWith("org.springframework.data.")
                        && !name.startsWith("jakarta.persistence.")
                        && !name.startsWith("jakarta.servlet.")
                        && !name.startsWith("javax.servlet."),
                "Application port leaks infrastructure type " + name);
    }
}
