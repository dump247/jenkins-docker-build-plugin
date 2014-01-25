package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.collect.ImmutableMap;
import hudson.util.FormValidation;
import net.dump247.docker.DirectoryBinding;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;

/** Test fixture for {@link DockerJobProperty}. */
public class DockerJobPropertyTest {
    @Test
    public void descriptor_doCheckEnvironment_empty_string() {
        DockerJobProperty.Descriptor descriptor = new DockerJobProperty.Descriptor();
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckEnvironment("").kind);
    }

    @Test
    public void descriptor_doCheckEnvironment_remove_var() {
        DockerJobProperty.Descriptor descriptor = new DockerJobProperty.Descriptor();
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckEnvironment("unset SOME_VAR").kind);
    }

    @Test
    public void descriptor_doCheckEnvironment_set_var_to_static_value() {
        DockerJobProperty.Descriptor descriptor = new DockerJobProperty.Descriptor();
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckEnvironment("SOME_VAR=value").kind);
    }

    @Test
    public void getEnvironment() {
        DockerJobProperty property = new DockerJobProperty("image", "", "" +
                "\n" +
                "# comment\n" +
                "unset REMOVE_FROM_ENV\n" +
                "ADD_TO_ENV=value1\n" +
                "\n" +
                "# comment\n" +
                "unset REMOVE_NOT_IN_ENV\n" +
                "OVERWRITE_ENV=value2\n" +
                "\n" +
                "SET_FROM_ENV_VAR=${REMOVE_FROM_ENV}\n" +
                "SET_FROM_MULTIPLE_VARS=pre_${env.OVERWRITE_ENV}_mid_${env.COPY_FROM_ENV}_suf\n" +
                "SYS_PROP=${sys.first.prop.key}:${SYS_SAME_AS_ENV}:${sys.SYS_SAME_AS_ENV}:${env.SYS_SAME_AS_ENV}");

        Properties systemProps = new Properties();
        systemProps.setProperty("first.prop.key", "sys_a");
        systemProps.setProperty("unused.key", "unused_sys_value");
        systemProps.setProperty("SYS_SAME_AS_ENV", "sys_d");

        Map<String, String> environment = property.getEnvironment(ImmutableMap.of(
                "REMOVE_FROM_ENV", "a",
                "OVERWRITE_ENV", "b",
                "COPY_FROM_ENV", "c",
                "SYS_SAME_AS_ENV", "env_d"), systemProps);

        assertEquals(7, environment.size());
        assertEquals("value1", environment.get("ADD_TO_ENV"));
        assertEquals("value2", environment.get("OVERWRITE_ENV"));
        assertEquals("c", environment.get("COPY_FROM_ENV"));
        assertEquals("a", environment.get("SET_FROM_ENV_VAR"));
        assertEquals("pre_b_mid_c_suf", environment.get("SET_FROM_MULTIPLE_VARS"));
        assertEquals("sys_a:env_d:sys_d:env_d", environment.get("SYS_PROP"));
        assertEquals("env_d", environment.get("SYS_SAME_AS_ENV"));
    }

    @Test
    public void getEnvironment_key_not_found() {
        try {
            new DockerJobProperty("image", "", "no_key=${no_such_key}")
                    .getEnvironment(ImmutableMap.<String, String>of(), new Properties());
        } catch (RuntimeException ex) {
            assertEquals("Key not found: no_such_key", ex.getMessage());
        }

        try {
            new DockerJobProperty("image", "", "no_key=${env.no_such_key}")
                    .getEnvironment(ImmutableMap.<String, String>of(), new Properties());
        } catch (RuntimeException ex) {
            assertEquals("Key not found: env.no_such_key", ex.getMessage());
        }

        try {
            new DockerJobProperty("image", "", "no_key=${sys.no_such_key}")
                    .getEnvironment(ImmutableMap.<String, String>of(), new Properties());
        } catch (RuntimeException ex) {
            assertEquals("Key not found: sys.no_such_key", ex.getMessage());
        }
    }

    @Test
    public void getDirectoryBindings() {
        DockerJobProperty property = new DockerJobProperty("image", "" +
                "/etc/a\n" +
                "\n" +
                "/etc/b:\n" +
                "/etc/c::\n" +
                "# comment\n" +
                "/etc/d:/etc/d\n" +
                "/etc/e:/etc/f\n" +
                "/etc/g:/etc/g:\n" +
                "/etc/h:/etc/i:\n" +
                "/etc/j:/etc/k:r\n" +
                "/etc/l:/etc/m:rw", "");

        List<DirectoryBinding> bindings = property.getDirectoryBindings(ImmutableMap.<String, String>of(), new Properties());

        assertEquals(9, bindings.size());
        assertBinding("/etc/a", "/etc/a", DirectoryBinding.Access.READ_WRITE, bindings.get(0));
        assertBinding("/etc/b", "/etc/b", DirectoryBinding.Access.READ_WRITE, bindings.get(1));
        assertBinding("/etc/c", "/etc/c", DirectoryBinding.Access.READ_WRITE, bindings.get(2));
        assertBinding("/etc/d", "/etc/d", DirectoryBinding.Access.READ_WRITE, bindings.get(3));
        assertBinding("/etc/e", "/etc/f", DirectoryBinding.Access.READ_WRITE, bindings.get(4));
        assertBinding("/etc/g", "/etc/g", DirectoryBinding.Access.READ_WRITE, bindings.get(5));
        assertBinding("/etc/h", "/etc/i", DirectoryBinding.Access.READ_WRITE, bindings.get(6));
        assertBinding("/etc/j", "/etc/k", DirectoryBinding.Access.READ, bindings.get(7));
        assertBinding("/etc/l", "/etc/m", DirectoryBinding.Access.READ_WRITE, bindings.get(8));
    }

    @Test
    public void getDirectoryBindings_with_vars() {
        DockerJobProperty property = new DockerJobProperty("image", "" +
                "${ENV_VAR}\n" +
                "${env.ENV_VAR}\n" +
                "${sys.first.prop.key}\n" +
                "${env.SYS_SAME_AS_ENV}\n" +
                "${sys.SYS_SAME_AS_ENV}\n" +
                "${ENV_VAR}:${sys.first.prop.key}\n" +
                "${ENV_VAR}:${sys.first.prop.key}:r", "");

        Properties systemProps = new Properties();
        systemProps.setProperty("first.prop.key", "sys_a");
        systemProps.setProperty("unused.key", "unused_sys_value");
        systemProps.setProperty("SYS_SAME_AS_ENV", "sys_d");

        List<DirectoryBinding> bindings = property.getDirectoryBindings(ImmutableMap.of(
                "ENV_VAR", "env_a",
                "SYS_SAME_AS_ENV", "env_b"), systemProps);

        assertEquals(7, bindings.size());
        assertBinding("env_a", "env_a", DirectoryBinding.Access.READ_WRITE, bindings.get(0));
        assertBinding("env_a", "env_a", DirectoryBinding.Access.READ_WRITE, bindings.get(1));
        assertBinding("sys_a", "sys_a", DirectoryBinding.Access.READ_WRITE, bindings.get(2));
        assertBinding("env_b", "env_b", DirectoryBinding.Access.READ_WRITE, bindings.get(3));
        assertBinding("sys_d", "sys_d", DirectoryBinding.Access.READ_WRITE, bindings.get(4));
        assertBinding("env_a", "sys_a", DirectoryBinding.Access.READ_WRITE, bindings.get(5));
        assertBinding("env_a", "sys_a", DirectoryBinding.Access.READ, bindings.get(6));
    }

    @Test
    public void verify_default_environment_and_bindings() {
        DockerJobProperty.Descriptor descriptor = new DockerJobProperty.Descriptor();
        String defaultBindings = descriptor.defaultDirectoryBindings();
        String defaultEnvironment = descriptor.defaultEnvironment();

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckDirectoryBindings(defaultBindings).kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckEnvironment(defaultEnvironment).kind);
    }

    private void assertBinding(String expectedHost, String expectedContainer, DirectoryBinding.Access expectedAccess, DirectoryBinding binding) {
        assertEquals(expectedHost, binding.getHostPath());
        assertEquals(expectedContainer, binding.getContainerPath());
        assertEquals(expectedAccess, binding.getAccess());
    }
}
