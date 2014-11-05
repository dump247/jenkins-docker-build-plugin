package net.dump247.docker;

import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

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

    @BeforeMethod
    public void setUp() {
        _client = new DockerClient(API_URL);
        _containerIds = new ArrayList<String>();
        _imageIds = new ArrayList<String>();
    }

    @AfterMethod
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

    @Test(expectedExceptions = ImageNotFoundException.class)
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
            assertEquals(event.getCode(), ProgressEvent.Code.Ok, "index: " + index);

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
        assertEquals(createResponse.getWarnings().size(), 0);

        // Start the container and wait for the command to finish
        _client.startContainer(createResponse.getContainerId());
        WaitContainerResponse waitResponse = _client.waitContainer(createResponse.getContainerId());

        assertEquals(waitResponse.getStatusCode(), 0);
    }

    @Test
    public void capture_container_output() throws Exception {
        assertEquals(
                runCommand(new CreateContainerRequest()
                        .withCommand("/bin/bash", "-c", "echo test out; echo test err >&2")),
                new String[]{
                        "test out\n",
                        "test err\n"
                });
    }

    private static String[] readLines(InputStream s) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(s));
        ArrayList<String> lines = new ArrayList<String>();
        String line;

        while ((line = reader.readLine()) != null) {
            lines.add(line);
        }

        return lines.toArray(new String[0]);
    }

    @Test
    public void attachContainerStreams() throws Exception {
        String containerId = createContainer(new CreateContainerRequest()
                .withCommand("/bin/bash", "-c", "read ddd; echo [${ddd}]; echo test err 1>&2")
                .withImage(UBUNTU)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withAttachStdin(true)
                .withStdinOnce(true)
                .withOpenStdin(true));

        final DockerClient.ContainerStreams streams = _client.attachContainerStreams(containerId);

        _client.startContainer(containerId);

        streams.stdin.write("some input\n".getBytes());

        Future<String[]> stdout = Executors.newSingleThreadExecutor().submit(new Callable<String[]>() {
            @Override
            public String[] call() throws Exception {
                return readLines(streams.stdout);
            }
        });

        String[] stderr = readLines(streams.stderr);

        assertEquals(_client.waitContainer(containerId).getStatusCode(), 0);
        assertEquals(stdout.get(), new String[]{"[some input]"});
        assertEquals(stderr, new String[]{"test err"});
    }

    @Test
    public void container_working_dir() throws Exception {
        assertEquals(
                runCommand(new CreateContainerRequest()
                        .withCommand("/bin/pwd")
                        .withWorkingDir("/tmp")),
                new String[]{
                        "/tmp\n",
                        ""
                });
    }

    @Test
    public void container_environment() throws Exception {
        assertEquals(
                new String[] {
                        "the var value\n",
                        ""
                },
                runCommand(new CreateContainerRequest()
                        .withCommand("/bin/bash", "-c", "echo $MY_VAR")
                        .withEnvironmentVar("MY_VAR", "the var value")));
    }

    @Test
    public void bind_directory() throws Exception {
        String containerId = createContainer(new CreateContainerRequest()
                .withImage(UBUNTU)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withVolumes("/my/vagrant")
                .withCommand("/bin/bash", "-c", "if [ ! -f /my/vagrant/LICENSE ]; then exit 1; fi"));

        _client.startContainer(new StartContainerRequest()
                .withContainerId(containerId)
                .withBinding("/vagrant", "/my/vagrant"));
        assertEquals(_client.waitContainer(containerId).getStatusCode(), 0);
    }

    @Test
    public void bind_directory_read_only() throws Exception {
        String containerId = createContainer(new CreateContainerRequest()
                .withImage(UBUNTU)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withVolumes("/my/vagrant")
                .withCommand("/bin/bash", "-c", "echo data > /my/vagrant/testfile"));

        OutputReader reader = new OutputReader(containerId);

        _client.startContainer(new StartContainerRequest()
                .withContainerId(containerId)
                .withBinding("/vagrant", "/my/vagrant", DirectoryBinding.Access.READ));
        assertEquals(_client.waitContainer(containerId).getStatusCode(), 1);

        String[] output = reader.join();

        assertEquals(output[0], "");
        assertTrue(output[1].contains("Read-only file system"));
    }

    @Test
    public void bind_directory_read_write() throws Exception {
        String containerId = createContainer(new CreateContainerRequest()
                .withImage(UBUNTU)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withVolumes("/my/vagrant")
                .withCommand("/bin/bash", "-c", "" +
                        "echo data > /my/vagrant/testfile; " +
                        "if [ ! -f /my/vagrant/testfile ]; then exit 1; fi; " +
                        "rm /my/vagrant/testfile"));

        _client.startContainer(new StartContainerRequest()
                .withContainerId(containerId)
                .withBinding("/vagrant", "/my/vagrant", DirectoryBinding.Access.READ_WRITE));
        assertEquals(_client.waitContainer(containerId).getStatusCode(), 0);
    }

    private String createContainer(CreateContainerRequest request) throws DockerException {
        _client.pullImage(request.getImage());
        _imageIds.add(request.getImage());

        CreateContainerResponse response = _client.createContainer(request);
        _containerIds.add(response.getContainerId());
        assertEquals(response.getWarnings().size(), 0);
        return response.getContainerId();
    }

    private String[] runCommand(CreateContainerRequest request) throws Exception {
        String containerId = createContainer(request
                .withImage(UBUNTU)
                .withAttachStderr(true)
                .withAttachStdout(true));

        OutputReader reader = new OutputReader(containerId);

        _client.startContainer(containerId);
        assertEquals(_client.waitContainer(containerId).getStatusCode(), 0);

        return reader.join();
    }

    private class OutputReader {
        private StreamCopyThread _stdoutCopyThread;
        private ByteArrayOutputStream _stdoutBuffer;
        private StreamCopyThread _stderrCopyThread;
        private ByteArrayOutputStream _stderrBuffer;

        public OutputReader(String containerId) throws DockerException {
            AttachResponse attachResponse = _client.attachContainer(containerId);

            _stdoutBuffer = new ByteArrayOutputStream();
            _stdoutCopyThread = new StreamCopyThread("stdoutCopier", attachResponse.getStdout(), _stdoutBuffer);
            _stdoutCopyThread.start();

            _stderrBuffer = new ByteArrayOutputStream();
            _stderrCopyThread = new StreamCopyThread("stderrCopier", attachResponse.getStderr(), _stderrBuffer);
            _stderrCopyThread.start();
        }

        public String[] join() throws Exception {
            _stdoutCopyThread.join();
            _stderrCopyThread.join();

            return new String[] {new String(_stdoutBuffer.toByteArray()), new String(_stderrBuffer.toByteArray())};
        }
    }
}
