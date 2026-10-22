package com.icbc.lineage.app;

public class UploadException extends IllegalArgumentException {
    private final int status;

    public UploadException(String message) {
        this(message, Status.UNPROCESSABLE_ENTITY);
    }

    public UploadException(String message, Status status) {
        super(message);
        this.status = status.code;
    }

    public int status() {
        return status;
    }

    public enum Status {
        NOT_FOUND(404), CONFLICT(409), UNPROCESSABLE_ENTITY(422),
        PAYLOAD_TOO_LARGE(413), INTERNAL_SERVER_ERROR(500);

        private final int code;

        Status(int code) {
            this.code = code;
        }
    }
}
