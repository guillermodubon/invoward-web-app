package io.github.guillermodubon.invoward.notification.application.port;

import io.github.guillermodubon.invoward.notification.application.model.EmailDeliveryReceipt;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;

/** Synchronously submits one transactional email through a configured provider. */
public interface EmailSender {

    EmailDeliveryReceipt send(TransactionalEmail email);
}
