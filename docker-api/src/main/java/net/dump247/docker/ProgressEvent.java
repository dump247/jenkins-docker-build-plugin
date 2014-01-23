package net.dump247.docker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Progress information. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProgressEvent {
    public enum Code {
        Ok(200),
        NotFound(404),
        Error(500);

        public final int value;

        private Code(int value) {
            this.value = value;
        }

        public static Code fromValue(int value) {
            switch (value) {
                case 200:
                    return Ok;

                case 404:
                    return NotFound;

                default:
                    return Error;
            }
        }
    }

    public static final String NULL_ID = "000000000000";

    @JsonProperty("status")
    private String _statusStr;

    @JsonProperty("error")
    private String _errorStatusStr;

    @JsonProperty("id")
    private String _id;

    @JsonProperty("errorDetail")
    private ErrorDetail _errorDetail;

    @JsonProperty("progressDetail")
    private ProgressDetail _progressDetail = new ProgressDetail();

    @JsonProperty("progress")
    private String _progress;

    public String getStatusMessage() {
        if (_statusStr != null) {
            return _statusStr;
        }

        if (_errorStatusStr != null) {
            return _errorStatusStr;
        }

        return "OK";
    }

    public String getId() {
        return _id == null ? NULL_ID : _id;
    }

    public String getDetailMessage() {
        if (_errorDetail != null) {
            return checkDetailMessage(_errorDetail.message);
        }

        return checkDetailMessage(_progress);
    }

    private String checkDetailMessage(String value) {
        if (value == null || getStatusMessage().equals(value)) {
            return "";
        }

        return value;
    }

    public Code getCode() {
        return Code.fromValue(getCodeValue());
    }

    public int getCodeValue() {
        if (_errorDetail != null) {
            return _errorDetail.code;
        }

        return Code.Ok.value;
    }

    public long getCurrent() {
        return _progressDetail.current;
    }

    public long getTotal() {
        return _progressDetail.total;
    }

    public long getStart() {
        return _progressDetail.start;
    }

    @Override
    public String toString() {
        return "ProgressEvent{" +
                "statusMessage=" + getStatusMessage() + "; " +
                "id=" + getId() + "; " +
                "detailMessage=" + getDetailMessage() + "; " +
                "code=" + getCode() + "; " +
                "codeValue=" + getCodeValue() + "; " +
                "current=" + getCurrent() + "; " +
                "total=" + getTotal() + "; " +
                "start=" + getStart() +
                "}";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ErrorDetail {
        public int code;
        public String message;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ProgressDetail {
        public long current;
        public long total;
        public long start;
    }
}