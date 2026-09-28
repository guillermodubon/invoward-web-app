package io.github.guillermodubon.invoward.notification.infrastructure.email.disabled;

import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.notification.application.model.EmailDeliveryReceipt;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;

public final class DisabledEmailSender implements EmailSender {

    @Override
    public EmailDeliveryReceipt send(TransactionalEmail email) {
        throw new EmailDeliveryException(
                EmailDeliveryFailure.CONFIGURATION,
                "Transactional email delivery is disabled");
    }
}
