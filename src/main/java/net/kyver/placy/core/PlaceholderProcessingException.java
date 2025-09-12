package net.kyver.placy.core;

public class PlaceholderProcessingException extends RuntimeException {

    private final String errorCode;
    private final long timestamp;

    public PlaceholderProcessingException(String message) {
        super(message);
        this.errorCode = "GENERAL_ERROR";
        this.timestamp = System.currentTimeMillis();
    }

    public PlaceholderProcessingException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "GENERAL_ERROR";
        this.timestamp = System.currentTimeMillis();
    }

    public PlaceholderProcessingException(String message, Throwable cause, String errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
        this.timestamp = System.currentTimeMillis();
    }

    public String getErrorCode() {
        return errorCode;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
