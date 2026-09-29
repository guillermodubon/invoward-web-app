package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.exception.DuplicateEmailException;
import io.github.guillermodubon.invoward.identity.application.model.RegistrationOutcome;
import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegisterUserServiceTest {

    private static final String RAW_PASSWORD = "a sufficiently long passphrase";
    private static final String PASSWORD_HASH = "$argon2id$test-only-hash";

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private RegisterUserTransaction registrationTransaction;

    private RegisterUserService service;

    @BeforeEach
    void setUp() {
        service = new RegisterUserService(userAccountRepository, passwordHasher, registrationTransaction);
    }

    @Test
    void normalizesInputHashesPasswordAndStartsPendingRegistration() {
        when(passwordHasher.hash(RAW_PASSWORD)).thenReturn(PASSWORD_HASH);
        when(userAccountRepository.existsByNormalizedEmail("user@example.com")).thenReturn(false);

        RegistrationOutcome outcome = service.register(
                " Guillermo Hernández ", " User@Example.COM ", RAW_PASSWORD);

        assertEquals(RegistrationOutcome.ACCEPTED, outcome);
        InOrder order = inOrder(passwordHasher, userAccountRepository, registrationTransaction);
        order.verify(passwordHasher).hash(RAW_PASSWORD);
        order.verify(userAccountRepository).existsByNormalizedEmail("user@example.com");
        order.verify(registrationTransaction).createPendingRegistration(
                "Guillermo Hernández", "user@example.com", PASSWORD_HASH);
    }

    @Test
    void existingEmailReturnsGenericAcceptedOutcomeWithoutCreatingRegistration() {
        when(passwordHasher.hash(RAW_PASSWORD)).thenReturn(PASSWORD_HASH);
        when(userAccountRepository.existsByNormalizedEmail("user@example.com")).thenReturn(true);

        RegistrationOutcome outcome = service.register("Existing User", "USER@example.com", RAW_PASSWORD);

        assertEquals(RegistrationOutcome.ACCEPTED, outcome);
        verify(registrationTransaction, never()).createPendingRegistration(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void databaseUniquenessRaceReturnsSameGenericAcceptedOutcome() {
        when(passwordHasher.hash(RAW_PASSWORD)).thenReturn(PASSWORD_HASH);
        when(userAccountRepository.existsByNormalizedEmail("user@example.com")).thenReturn(false);
        doThrow(new DuplicateEmailException())
                .when(registrationTransaction)
                .createPendingRegistration("New User", "user@example.com", PASSWORD_HASH);

        assertEquals(RegistrationOutcome.ACCEPTED,
                service.register("New User", "user@example.com", RAW_PASSWORD));
    }

    @Test
    void unrelatedPersistenceFailureIsNotHiddenAsDuplicate() {
        when(passwordHasher.hash(RAW_PASSWORD)).thenReturn(PASSWORD_HASH);
        when(userAccountRepository.existsByNormalizedEmail("user@example.com")).thenReturn(false);
        doThrow(new DataIntegrityViolationException("database failure"))
                .when(registrationTransaction)
                .createPendingRegistration("New User", "user@example.com", PASSWORD_HASH);

        assertThrows(DataIntegrityViolationException.class,
                () -> service.register("New User", "user@example.com", RAW_PASSWORD));
    }

    @Test
    void invalidDisplayNameEmailOrPasswordStopsBeforeHashingOrPersistence() {
        assertThrows(IllegalArgumentException.class,
                () -> service.register(" \t\n ", "user@example.com", RAW_PASSWORD));
        assertThrows(IllegalArgumentException.class,
                () -> service.register("New User", " \t\n ", RAW_PASSWORD));
        assertThrows(IllegalArgumentException.class,
                () -> service.register("New User", "user@example.com", "short"));
        assertThrows(IllegalArgumentException.class,
                () -> service.register("New User", "user@example.com", "               "));

        verifyNoInteractions(passwordHasher, userAccountRepository, registrationTransaction);
    }
}
