package net.dump247.docker;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for {@link DockerClient}.
 * <p/>
 * Requires that a docker API be serving requests from local port 49000.
 * An easy method is to use https://github.com/dotcloud/docker/blob/master/Dockerfile
 * and add the following to /etc/default/docker: <em><pre>DOCKER_OPTS="-H tcp://0.0.0.0:49000"</pre></em>.
 */
public class DockerClientIT {
    private static final URI API_URL = URI.create("http://localhost:49000");
    private static final String UBUNTU = "ubuntu";

    private DockerClient _client;

    private List<String> _containerIds;
    private List<String> _imageIds;

    @BeforeClass
    public static void setUpFixture() throws Exception {
        // Ensure ubuntu image is available for all tests so tests run faster
        // Tests for the pull functionality should use a different image
        new DockerClient(API_URL).pullImage(UBUNTU);
    }

    @AfterClass
    public static void tearDownFixture() throws Exception {
        new DockerClient(API_URL).removeImage(UBUNTU);
    }

    @Before
    public void setUp() {
        _client = new DockerClient(API_URL);
        _containerIds = new ArrayList<String>();
        _imageIds = new ArrayList<String>();
    }

    @After
    public void tearDown() throws Exception {
        for (String containerId : _containerIds) {
            _client.removeContainer(containerId);
        }

        for (String imageId : _imageIds) {
            if (!imageId.equals(UBUNTU)) {
                _client.removeImage(imageId);
            }
        }
    }

    @Test
    public void version() throws Exception {
        DockerVersionResponse response = _client.version();
        assertTrue("Version=" + response.getVersion(), response.getVersion().matches("^\\d+\\.\\d+\\.\\d+$"));
        assertTrue("GitCommit=" + response.getGitCommit(), response.getGitCommit().matches("^[a-f0-9]{7}$"));
        assertTrue("GoVersion=" + response.getGoVersion(), response.getGoVersion().matches("^go\\d+\\.\\d+$"));
        assertTrue("Arch=" + response.getArch(), response.getArch().length() > 0);
        assertTrue("KernelVersion=" + response.getKernelVersion(), response.getKernelVersion().length() > 0);
        assertTrue("Os=" + response.getOs(), response.getOs().length() > 0);
    }

    @Test(expected = ImageNotFoundException.class)
    public void pullImage_that_does_not_exist() throws Exception {
        _client.pullImage("no-such-image");
    }

    @Test
    public void pullImage_events() throws Exception {
        final List<ProgressEvent> events = new ArrayList<ProgressEvent>();

        _imageIds.add("stackbrew/ubuntu");
        _client.pullImage("stackbrew/ubuntu", new ProgressListener() {
            @Override
            public void progress(final ProgressEvent event) {
                events.add(event);
            }
        });

        int progressEvents = 0;
        int finalProgressEvents = 0;

        int index = 0;
        for (ProgressEvent event : events) {
            // All events should be success events (no errors)
            assertEquals("index: " + index, ProgressEvent.Code.Ok, event.getCode());

            // Always should have a status message
            assertTrue(event.getStatusMessage().length() > 0);

            // Status message should not equal detail message (detail message is optional)
            assertFalse(event.getDetailMessage().equals(event.getStatusMessage()));

            // ID is not empty
            assertTrue(event.getId().length() > 0);

            // Progress info should be valid
            assertTrue("index: " + index, event.getTotal() >= 0);
            assertTrue("index: " + index, event.getCurrent() >= 0);
            assertTrue("index: " + index, event.getTotal() >= event.getCurrent());

            if (event.getTotal() > 0) {
                progressEvents += 1;

                if (event.getTotal() == event.getCurrent()) {
                    finalProgressEvents += 1;
                }
            }
        }

        assertTrue(progressEvents > 0);
        assertTrue(finalProgressEvents > 0);
    }

    @Test
    public void simplest_container_run() throws Exception {
        // Pull the image so it is available locally
        _client.pullImage(UBUNTU);

        // Create a container from the image
        CreateContainerResponse createResponse = _client.createContainer(new CreateContainerRequest()
                .withCommand("/usr/bin/env")
                .withImage(UBUNTU));

        assertTrue(createResponse.getContainerId().length() > 0);
        _containerIds.add(createResponse.getContainerId());
        assertEquals(0, createResponse.getWarnings().size());

        // Start the container and wait for the command to finish
        _client.startContainer(createResponse.getContainerId());
        WaitContainerResponse waitResponse = _client.waitContainer(createResponse.getContainerId());

        assertEquals(0, waitResponse.getStatusCode());
    }

    @Test
    public void capture_container_output() throws Exception {
        assertArrayEquals(
                new String[] {
                        "test out\n",
                        "test err\n"
                },
                runCommand(new CreateContainerRequest()
                        .withCommand("/bin/bash", "-c", "echo test out; echo test err >&2")));
    }

    @Test
    public void container_working_dir() throws Exception {
        assertArrayEquals(
                new String[] {
                        "/tmp\n",
                        ""
                },
                runCommand(new CreateContainerRequest()
                        .withCommand("/bin/pwd")
                        .withWorkingDir("/tmp")));
    }

    @Test
    public void container_environment() throws Exception {
        assertArrayEquals(
                new String[] {
                        "the var value\n",
                        ""
                },
                runCommand(new CreateContainerRequest()
                        .withCommand("/bin/bash", "-c", "echo $MY_VAR")
                        .withEnvironmentVar("MY_VAR", "the var value")));
    }

    private String createContainer(CreateContainerRequest request) throws DockerException {
        _client.pullImage(request.getImage());
        _imageIds.add(request.getImage());

        CreateContainerResponse response = _client.createContainer(request);
        _containerIds.add(response.getContainerId());
        assertEquals(0, response.getWarnings().size());
        return response.getContainerId();
    }

    private void waitForContainer(String containerId) throws DockerException {
        _client.startContainer(containerId);
        assertEquals(0, _client.waitContainer(containerId).getStatusCode());
    }

    private String[] runCommand(CreateContainerRequest request) throws Exception {
        String containerId = createContainer(request
                .withImage(UBUNTU)
                .withAttachStderr(true)
                .withAttachStdout(true));

        AttachResponse attachResponse = _client.attachContainer(containerId);

        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        StreamCopyThread stdoutCopy = new StreamCopyThread("stdoutCopier", attachResponse.getStdout(), stdoutBuffer);
        stdoutCopy.start();

        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
        StreamCopyThread stderrCopy = new StreamCopyThread("stderrCopier", attachResponse.getStderr(), stderrBuffer);
        stderrCopy.start();

        waitForContainer(containerId);

        stdoutCopy.join();
        stderrCopy.join();

        return new String[] {new String(stdoutBuffer.toByteArray()), new String(stderrBuffer.toByteArray())};
    }
}
