package dev.openallay.builder;

/** Stable native failure code; a failure never means an empty/air block. */
public final class BuilderException extends RuntimeException {
    private final String code;
    public BuilderException(String code, String message) { super(code + ": " + message); this.code = code; }
    public BuilderException(String code, String message, Throwable cause) { super(code + ": " + message, cause); this.code = code; }
    public String code() { return code; }
}
