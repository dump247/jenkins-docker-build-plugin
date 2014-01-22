package net.dump247.docker;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Progress information. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProgressMessage {
    @JsonProperty("status")
    private String _status;

    @JsonProperty("error")
    private String _error;

    @JsonProperty("id")
    private String _id;

    @JsonProperty("errorDetail")
    private ErrorDetail _errorDetail;

    @JsonIgnore
    private ProgressDetail _progressDetail;

    @JsonProperty("progress")
    private String _progress;

    public String getMessage() {
        if (_status != null) {
            return _status;
        }

        if (_error != null) {
            return _error;
        }

        if (_errorDetail != null) {
            return _errorDetail.getMessage();
        }

        return "";
    }

    public String getId() {
        return _id;
    }

    public ErrorDetail getErrorDetail() {
        return _errorDetail;
    }

    public boolean isError() {
        return _errorDetail != null;
    }

    public ProgressDetail getProgressDetail() {
        if (_progress != null && _progressDetail != null) {
            _progressDetail._message = _progress;
        }

        return _progressDetail;
    }

    @JsonProperty("progressDetail")
    private void setProgressDetail(ProgressDetail progressDetail) {
        if (progressDetail != null) {
            if (progressDetail._current == 0 && progressDetail._start == 0 && progressDetail._total == 0) {
                progressDetail = null;
            }
        }

        _progressDetail = progressDetail;
    }

    @Override
    public String toString() {
        return "ProgressMessage{" +
                "message=" + getMessage() + "; " +
                "id=" + getId() + "; " +
                "errorDetail=" + getErrorDetail() + "; " +
                "progressDetail=" + getProgressDetail() +
                "}";
    }

    public static class ErrorDetail {
        @JsonProperty("code")
        private int _code;

        @JsonProperty("message")
        private String _message;

        public int getCode() {
            return _code;
        }

        public String getMessage() {
            return _message;
        }

        @Override
        public String toString() {
            return "ErrorDetail{" +
                    "code=" + getCode() + "; " +
                    "message=" + getMessage() +
                    "}";
        }
    }

    public static class ProgressDetail {
        @JsonProperty("current")
        private long _current;

        @JsonProperty("total")
        private long _total;

        @JsonProperty("start")
        private long _start;

        @JsonIgnore
        private String _message;

        public long getCurrent() {
            return _current;
        }

        public long getTotal() {
            return _total;
        }

        public long getStart() {
            return _start;
        }

        public String getMessage() {
            return _message;
        }

        @Override
        public String toString() {
            return "ProgressDetail{" +
                    "current=" + getCurrent() + "; " +
                    "total=" + getTotal() + "; " +
                    "start=" + getStart() + "; " +
                    "message=" + getMessage() +
                    "}";
        }
    }
}