package io.github.ainouss.kitchensink.facade.shadow;

import java.nio.charset.StandardCharsets;

import io.github.ainouss.kitchensink.facade.control.Control.Side;

/**
 * One side's answer to a read, as the compare needs it, or the error if there was no answer.
 */
public record SideResponse(Side side, int status, String contentType, byte[] body, String error) {

    public static SideResponse failed(Side side, String error) {
        return new SideResponse(side, 0, null, new byte[0], error);
    }

    public boolean isFailure() {
        return error != null;
    }

    public boolean isJson() {
        return contentType != null && contentType.toLowerCase().contains("json");
    }

    /** The body as text, cut at {@code max} characters. */
    public String text(int max) {
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() <= max ? text : text.substring(0, max) + "… (" + body.length + " bytes)";
    }
}
