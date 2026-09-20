package gsb.web;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public java.util.Map<String, Object> body() {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", java.util.Map.of("code", code, "message", getMessage()));
        body.put("committed", false);
        return body;
    }
}
