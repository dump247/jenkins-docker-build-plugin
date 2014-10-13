package net.dump247.jenkins.plugins.dockerbuild.log;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public class Logger {
    private final java.util.logging.Logger _wrapped;

    public static Logger get(Class<?> type) {
        return new Logger(java.util.logging.Logger.getLogger(type.getName()));
    }

    private Logger(java.util.logging.Logger wrapped) {
        _wrapped = wrapped;
    }

    public void debug(String msg) {
        _wrapped.log(Level.FINE, msg);
    }

    public void debug(String msg, Object arg1) {
        log(Level.FINE, msg, arg1);
    }

    public void debug(String msg, Object... params) {
        log(Level.FINE, msg, params);
    }

    public void info(String msg) {
        _wrapped.log(Level.INFO, msg);
    }

    public void info(String msg, Object arg1) {
        log(Level.INFO, msg, arg1);
    }

    public void info(String msg, Object... params) {
        log(Level.INFO, msg, params);
    }

    public void warn(String msg) {
        _wrapped.log(Level.WARNING, msg);
    }

    public void warn(String msg, Object arg1) {
        log(Level.WARNING, msg, arg1);
    }

    public void warn(String msg, Object... params) {
        log(Level.WARNING, msg, params);
    }

    private void log(Level level, String msg, Object arg1) {
        if (arg1 instanceof Throwable) {
            _wrapped.log(level, msg, (Throwable) arg1);
        } else {
            _wrapped.log(level, msg, arg1);
        }
    }

    private void log(Level level, String msg, Object... params) {
        if (params != null && params.length > 0 && params[params.length - 1] instanceof Throwable) {
            if (_wrapped.isLoggable(level)) {
                LogRecord lr = new LogRecord(level, msg);
                lr.setThrown((Throwable) params[params.length - 1]);
                lr.setParameters(Arrays.copyOf(params, params.length - 1));
                _wrapped.log(lr);
            }
        } else {
            _wrapped.log(level, msg, params);
        }
    }
}
