package net.dump247.jenkins.plugins.dockerbuild;

import org.apache.commons.lang.text.StrLookup;

import java.util.Map;
import java.util.Properties;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Lookup variable values for replacement in docker build configuration.
 * <p/>
 * Variable substitution is used in configuration options like environment
 * and directory binding. This lookup first checks the system properties
 * and then environment variables for the given key.
 */
public class JobConfigStrLookup extends StrLookup {
    private static final String SYS_PREFIX = "sys.";
    private static final String ENV_PREFIX = "env.";

    private final Map<String, String> _environment;
    private final Properties _properties;

    /**
     * Initialize a new instance with the given environment and system properties.
     *
     * @param environment environment variables
     * @param properties  system properties
     */
    public JobConfigStrLookup(Map<String, String> environment, Properties properties) {
        _environment = checkNotNull(environment);
        _properties = checkNotNull(properties);
    }

    @Override
    public String lookup(final String key) {
        String value;

        if (key.startsWith(SYS_PREFIX)) {
            value = _properties.getProperty(key.substring(SYS_PREFIX.length()));
        } else if (key.startsWith(ENV_PREFIX)) {
            value = _environment.get(key.substring(ENV_PREFIX.length()));
        } else {
            value = _environment.get(key);
        }

        if (value == null) {
            throw new RuntimeException("Key not found: " + key);
        }

        return value;
    }
}
