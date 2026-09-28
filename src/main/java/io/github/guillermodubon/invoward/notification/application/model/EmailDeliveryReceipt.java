package io.github.guillermodubon.invoward.notification.application.model;

/** Provider-neutral acknowledgement of a successfully submitted email. */
public record EmailDeliveryReceipt(String providerMessageId) {

    public EmailDeliveryReceipt {
        if (providerMessageId == null || providerMessageId.isBlank()) {
            throw new IllegalArgumentException("providerMessageId must not be blank");
        }
    }
}
