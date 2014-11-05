package net.dump247.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.sun.jersey.api.client.ClientHandlerException;
import com.xebialabs.restito.semantics.Action;
import com.xebialabs.restito.semantics.Call;
import com.xebialabs.restito.server.StubServer;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.Arrays;

import static com.xebialabs.restito.builder.stub.StubHttp.whenHttp;
import static com.xebialabs.restito.semantics.Action.contentType;
import static com.xebialabs.restito.semantics.Action.status;
import static com.xebialabs.restito.semantics.Action.stringContent;
import static com.xebialabs.restito.semantics.Condition.custom;
import static com.xebialabs.restito.semantics.Condition.delete;
import static com.xebialabs.restito.semantics.Condition.get;
import static com.xebialabs.restito.semantics.Condition.post;
import static com.xebialabs.restito.semantics.Condition.withHeader;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

/** Test fixture for {@link DockerClient}. */
public class DockerClientTest {
    private StubServer _stubServer;
    private URI _serverEndpoint;

    @BeforeMethod
    public void setUp() {
        _stubServer = new StubServer().run();
        _serverEndpoint = URI.create("http://localhost:" + _stubServer.getPort());
    }

    @AfterMethod
    public void tearDown() {
        _stubServer.stop();
    }

    @Test
    public void version() throws Exception {
        whenHttp(_stubServer)
                .match(
                        get("/" + DockerClient.API_VERSION + "/version"),
                        withHeader("Content-Type", "application/json"),
                        withHeader("Accept", "application/json")
                )
                .then(
                        contentType("application/json"),
                        status(HttpStatus.OK_200),
                        stringContent("{\"Version\":\"0.6.6\",\"GitCommit\":\"6d42040\",\"GoVersion\":\"go1.2rc3\"}")
                );

        DockerVersionResponse version = new DockerClient(_serverEndpoint).version();

        assertEquals(version.getVersion(), "0.6.6");
        assertEquals(version.getGitCommit(), "6d42040");
        assertEquals(version.getGoVersion(), "go1.2rc3");
        assertNull(version.getArch());
        assertNull(version.getKernelVersion());
        assertNull(version.getOs());
    }

    @Test
    public void version_with_kernel_props() throws Exception {
        whenHttp(_stubServer)
                .match(
                        get("/" + DockerClient.API_VERSION + "/version"),
                        withHeader("Content-Type", "application/json"),
                        withHeader("Accept", "application/json")
                )
                .then(
                        contentType("application/json"),
                        status(HttpStatus.OK_200),
                        stringContent("{\"Version\":\"0.6.6\",\"GitCommit\":\"6d42040\",\"GoVersion\":\"go1.2rc3\", \"Arch\": \"amd64\", \"KernelVersion\": \"3.8.0-35-generic\", \"Os\": \"linux\"}")
                );

        DockerVersionResponse version = new DockerClient(_serverEndpoint).version();

        assertEquals(version.getVersion(), "0.6.6");
        assertEquals(version.getGitCommit(), "6d42040");
        assertEquals(version.getGoVersion(), "go1.2rc3");
        assertEquals(version.getArch(), "amd64");
        assertEquals(version.getKernelVersion(), "3.8.0-35-generic");
        assertEquals(version.getOs(), "linux");
    }

    @Test(expectedExceptions = ClientHandlerException.class)
    public void version_with_docker_endpoint_down() throws Exception {
        new DockerClient(URI.create("http://localhost:60000")).version();
    }

    @Test(expectedExceptions = DockerException.class)
    public void version_with_server_error() throws Exception {
        whenHttp(_stubServer)
                .match(
                        get("/" + DockerClient.API_VERSION + "/version"),
                        withHeader("Content-Type", "application/json"),
                        withHeader("Accept", "application/json")
                )
                .then(
                        contentType("application/json"),
                        status(HttpStatus.INTERNAL_SERVER_ERROR_500)
                );

        new DockerClient(_serverEndpoint).version();
    }

    @Test
    public void createContainer() throws Exception {
        whenHttp(_stubServer)
                .match(
                        post("/" + DockerClient.API_VERSION + "/containers/create"),
                        custom(new Predicate<Call>() {
                            public boolean apply(@Nullable final Call input) {
                                assertJson("{\"Volumes\":{\"/other/dir\":{}},\"Privileged\":true,\"Image\":\"some/image\",\"Cmd\":[\"do\",\"something\",\"nice\"],\"Hostname\":\"hostname\",\"User\":\"user\",\"Memory\":20000,\"AttachStdin\":true,\"AttachStdout\":true,\"AttachStderr\":true,\"Tty\":true,\"OpenStdin\":true,\"StdinOnce\":true,\"Env\":[\"vara=valueb\",\"varb=valuec\"],\"WorkingDir\":\"/home/something\"}", input.getPostBody());
                                return true;
                            }
                        }),
                        withHeader("Content-Type", "application/json"),
                        withHeader("Accept", "application/json")
                )
                .then(
                        contentType("application/json"),
                        stringContent("{\"Id\":\"572ec83f5139\",\"Warnings\":[\"warning a\", \"warning b\"]}")
                );

        CreateContainerResponse response = new DockerClient(_serverEndpoint).createContainer(
                new CreateContainerRequest()
                        .withAttachStderr(true)
                        .withAttachStdin(true)
                        .withAttachStdout(true)
                        .withCommand("do", "something", "nice")
                        .withEnvironmentVar("vara", "valueb")
                        .withEnvironmentVar("varb", "valuec")
                        .withHostname("hostname")
                        .withImage("some/image")
                        .withMemoryLimit(10000)
                        .withOpenStdin(true)
                        .withPrivileged(true)
                        .withStdinOnce(true)
                        .withSwapLimit(20000)
                        .withTty(true)
                        .withUser("user")
                        .withWorkingDir("/home/something")
                        .withVolumes("/other/dir"));

        assertEquals(response.getContainerId(), "572ec83f5139");
        assertEquals(response.getWarnings(), Arrays.asList("warning a", "warning b"));
    }

    @Test
    public void createContainer_basic_request() throws Exception {
        whenHttp(_stubServer)
                .match(
                        post("/" + DockerClient.API_VERSION + "/containers/create"),
                        custom(new Predicate<Call>() {
                            public boolean apply(@Nullable final Call input) {
                                return input.getPostBody().equals("{\"Image\":\"base\",\"Cmd\":[\"some\",\"command\"]}");
                            }
                        }),
                        withHeader("Content-Type", "application/json"),
                        withHeader("Accept", "application/json")
                )
                .then(
                        contentType("application/json"),
                        stringContent("{\"Id\":\"572ec83f5139\"}")
                );

        CreateContainerResponse response = new DockerClient(_serverEndpoint).createContainer(
                new CreateContainerRequest()
                        .withImage("base")
                        .withCommand("some", "command"));

        assertEquals(response.getContainerId(), "572ec83f5139");
        assertEquals(response.getWarnings().size(), 0);
    }

    private static void assertJson(String expected, String actual) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode expectedNode;
        JsonNode actualNode;

        try {
            expectedNode = mapper.readTree(expected);
            actualNode = mapper.readTree(actual);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        assertEquals(actualNode, expectedNode);
    }

    @Test
    public void startContainer_basic_request() throws Exception {
        whenHttp(_stubServer)
                .match(
                        post("/" + DockerClient.API_VERSION + "/containers/xyz/start"),
                        custom(new Predicate<Call>() {
                            public boolean apply(@Nullable final Call input) {
                                return input.getPostBody().equals("{}");
                            }
                        }),
                        withHeader("Content-Type", "application/json"),
                        withHeader("Accept", "application/json")
                )
                .then(
                        contentType("application/json"),
                        status(HttpStatus.NO_CONTENT_204)
                );

        new DockerClient(_serverEndpoint).startContainer(new StartContainerRequest()
                .withContainerId("xyz"));
    }

    @Test
    public void startContainer() throws Exception {
        whenHttp(_stubServer)
                .match(
                        post("/" + DockerClient.API_VERSION + "/containers/xyz/start"),
                        custom(new Predicate<Call>() {
                            public boolean apply(@Nullable final Call input) {
                                return input.getPostBody().equals("{\"Binds\":[\"/host:/container:rw\",\"/host2:/container2:r\"]}");
                            }
                        }),
                        withHeader("Content-Type", "application/json"),
                        withHeader("Accept", "application/json")
                )
                .then(
                        contentType("application/json"),
                        status(HttpStatus.NO_CONTENT_204)
                );

        new DockerClient(_serverEndpoint).startContainer(new StartContainerRequest()
                .withContainerId("xyz")
                .withBinding("/host", "/container")
                .withBinding("/host2", "/container2", DirectoryBinding.Access.READ));
    }

    @Test
    public void waitContainer() throws Exception {
        whenHttp(_stubServer)
                .match(
                        post("/" + DockerClient.API_VERSION + "/containers/xyz/wait"),
                        withHeader("Content-Type", "application/json"),
                        withHeader("Accept", "application/json")
                )
                .then(
                        contentType("application/json"),
                        status(HttpStatus.OK_200),
                        Action.custom(new Function<Response, Response>() {
                            @Override
                            public Response apply(@Nullable final Response input) {
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                }
                                return input;
                            }
                        })
                );

        new DockerClient(_serverEndpoint).waitContainer(new WaitContainerRequest()
                .withContainerId("xyz"));
    }

    @Test
    public void removeContainer() throws Exception {
        whenHttp(_stubServer)
                .match(
                        delete("/" + DockerClient.API_VERSION + "/containers/xyz"),
                        withHeader("Content-Type", "application/json"),
                        withHeader("Accept", "application/json")
                )
                .then(
                        contentType("application/json"),
                        status(HttpStatus.OK_200)
                );

        new DockerClient(_serverEndpoint).removeContainer(new RemoveContainerRequest()
                .withContainerId("xyz"));
    }

    @Test
    public void removeContainer_container_not_found() throws Exception {
        whenHttp(_stubServer)
                .match(
                        delete("/" + DockerClient.API_VERSION + "/containers/xyz"),
                        withHeader("Content-Type", "application/json"),
                        withHeader("Accept", "application/json")
                )
                .then(
                        contentType("application/json"),
                        status(HttpStatus.NOT_FOUND_404)
                );

        new DockerClient(_serverEndpoint).removeContainer(new RemoveContainerRequest()
                .withContainerId("xyz"));
    }

    @Test
    public void killContainer() throws Exception {
        whenHttp(_stubServer)
                .match(
                        post("/" + DockerClient.API_VERSION + "/containers/xyz/kill"),
                        withHeader("Content-Type", "application/json"),
                        withHeader("Accept", "application/json")
                )
                .then(
                        contentType("application/json"),
                        status(HttpStatus.OK_200)
                );

        new DockerClient(_serverEndpoint).killContainer(new KillContainerRequest()
                .withContainerId("xyz"));
    }
}
