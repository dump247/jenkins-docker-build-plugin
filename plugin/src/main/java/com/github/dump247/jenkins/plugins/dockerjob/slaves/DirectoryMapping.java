package com.github.dump247.jenkins.plugins.dockerjob.slaves;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;

public class DirectoryMapping {
    private static final Pattern MAPPING_PATTERN = Pattern.compile("^(/[^:]+):(/[^:]+)(?::(ro|rw))?$");

    private final String _hostPath;
    private final String _containerPath;
    private final Access _access;

    public DirectoryMapping(String hostPath, String containerPath, Access access) {
        _hostPath = checkNotNull(hostPath);
        _containerPath = checkNotNull(containerPath);
        _access = checkNotNull(access);
    }

    public String getHostPath() {
        return _hostPath;
    }

    public String getContainerPath() {
        return _containerPath;
    }

    public Access getAccess() {
        return _access;
    }

    public static enum Access {
        READ("ro"),
        READ_WRITE("rw");

        private final String _value;

        Access(String value) {
            _value = value;
        }

        public String value() {
            return _value;
        }
    }

    public static DirectoryMapping parse(String value) {
        checkNotNull(value);

        Matcher match = MAPPING_PATTERN.matcher(value);

        if (!match.matches()) {
            throw new IllegalArgumentException("Invalid directory mapping: " + value);
        }

        return new DirectoryMapping(
                match.group(1),
                match.group(2),
                match.group(3).equals("rw") ? Access.READ_WRITE : Access.READ);
    }
}
