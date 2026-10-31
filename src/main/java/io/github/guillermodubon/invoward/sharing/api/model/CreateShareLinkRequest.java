package io.github.guillermodubon.invoward.sharing.api.model;

import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkExpiryChoice;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Strict one-field request for creating a one-time share URL. */
public final class CreateShareLinkRequest {

    private String expiresIn;
    private boolean unknownProperty;
    private boolean expiresInSeen;

    public CreateShareLinkRequest() {
    }

    @JsonProperty("expiresIn")
    @NotNull(message = "A supported expiration choice is required")
    @Pattern(regexp = "(?:24_HOURS|7_DAYS|30_DAYS)", message = "A supported expiration choice is required")
    public String getExpiresIn() {
        return expiresIn;
    }

    @JsonProperty("expiresIn")
    public void setExpiresIn(String expiresIn) {
        if (expiresInSeen) {
            unknownProperty = true;
        }
        expiresInSeen = true;
        this.expiresIn = expiresIn;
    }

    /** Maps only the public API values accepted by validation to the application choice. */
    public ShareLinkExpiryChoice toExpiryChoice() {
        return switch (expiresIn) {
            case "24_HOURS" -> ShareLinkExpiryChoice.HOURS_24;
            case "7_DAYS" -> ShareLinkExpiryChoice.DAYS_7;
            case "30_DAYS" -> ShareLinkExpiryChoice.DAYS_30;
            default -> throw new IllegalStateException("Share expiry choice must be validated before mapping");
        };
    }

    @JsonAnySetter
    public void captureUnknownProperty(String name, Object value) {
        unknownProperty = true;
    }

    @AssertTrue(message = "Only expiresIn is accepted")
    public boolean isShapeValid() {
        return !unknownProperty;
    }

    @Override
    public String toString() {
        return "CreateShareLinkRequest[expiresIn="
                + (expiresIn == null ? "[INVALID]" : expiresIn)
                + ", unknownProperty=[REDACTED]]";
    }
}
