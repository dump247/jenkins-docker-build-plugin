package net.dump247.docker;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.core.util.MultivaluedMapImpl;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static java.lang.String.format;

/**
 * Client for interacting with docker.
 * <p/>
 * Only supports the docker API via TCP. You must bind docker to a TCP
 * port. For example: docker -H 127.0.0.1:4243
 */
public class DockerClient {
    /** Version of the docker API this client uses ({@value}). */
    public static final String API_VERSION = "v1.6";

    /** URI of the default local docker API endpoint (http://localhost:4243). */
    public static final URI DEFAULT_LOCAL_URI = URI.create("http://localhost:4243");

    public static final String APPLICATION_DOCKER_RAW_STREAM = "application/vnd.docker.raw-stream";
    public static final MediaType APPLICATION_DOCKER_RAW_STREAM_TYPE = MediaType.valueOf(APPLICATION_DOCKER_RAW_STREAM);

    private static final int STDOUT_STREAM = 1;
    private static final int STDERR_STREAM = 2;

    private final URI _apiEndpoint;
    private final Client _httpClient;

    /**
     * Initialize a new instance.
     *
     * @param dockerEndpoint docker API to interact with (e.g. http://127.0.0.1:4243)
     */
    public DockerClient(URI dockerEndpoint) {
        _apiEndpoint = UriBuilder.fromUri(dockerEndpoint).path(API_VERSION).build();
        _httpClient = Client.create();
    }

    /**
     * Get docker version information.
     *
     * @return version info
     * @throws DockerException        if the server reports an error
     * @throws ClientHandlerException if an error occurs sending the request or receiving the response
     *                                (i.e. server is not listing on specified port, etc)
     */
    public DockerVersionResponse version() throws DockerException {
        try {
            return api("version").get(DockerVersionResponse.class);
        } catch (UniformInterfaceException ex) {
            throw new DockerException("Failed to get docker version information: [uri=" + uri("version") + "]", ex);
        }
    }

    /**
     * Create a new client that communicates with the local docker service.
     *
     * @return local docker client
     * @see #DEFAULT_LOCAL_URI
     */
    public static DockerClient localClient() {
        return new DockerClient(DEFAULT_LOCAL_URI);
    }

    /**
     * Pull an image from the registry so it is available locally.
     * <p/>
     * This method blocks until the image is downloaded. As such, it may take a long time to
     * complete.
     *
     * @param request image pull options
     * @throws DockerException        if the server reports an error
     * @throws ImageNotFoundException if the requested image does not exist in the registry
     * @throws ClientHandlerException if an error occurs sending the request or receiving the response
     *                                (i.e. server is not listing on specified port, etc)
     */
    public void pullImage(PullImageRequest request) throws DockerException {
        pullImage(request, ProgressMonitor.NULL);
    }

    /**
     * Pull an image from the registry so it is available locally.
     * <p/>
     * This method blocks until the image is downloaded. As such, it may take a long time to
     * complete.
     *
     * @param request  image pull options
     * @param progress receive progress messages
     * @throws DockerException        if the server reports an error
     * @throws ImageNotFoundException if the requested image does not exist in the registry
     * @throws ClientHandlerException if an error occurs sending the request or receiving the response
     *                                (i.e. server is not listing on specified port, etc)
     */
    public void pullImage(PullImageRequest request, ProgressMonitor progress) throws DockerException {
        if (request == null) {
            throw new NullPointerException("request");
        }

        if (request.getImage() == null) {
            throw new IllegalArgumentException("request.image is required");
        }

        if (progress == null) {
            throw new NullPointerException("progress");
        }

        // There are a couple of oddities about this API:
        // 1. It appears to block until the image is downloaded, which may take a while
        // 2. The response body contains multiple json objects concatenated
        // 3. It always responds with 200, even if the response content indicates an error
        ProgressMessage response;

        try {
            WebResource resource = resource("images/create");

            MultivaluedMap<String, String> params = new MultivaluedMapImpl();
            params.add("fromImage", request.getImage());

            if (request.getTag().length() > 0) {
                params.add("tag", request.getTag());
            }

            response = readLastResponse(json(resource.queryParams(params)).post(ClientResponse.class), progress);
        } catch (IOException ex) {
            throw new DockerException("Error handling request: [uri=" + uri("images/create") + "]", ex);
        } catch (UniformInterfaceException ex) {
            switch (ex.getResponse().getStatus()) {
                case 500:
                    throw new DockerException("Server error", ex);
                default:
                    throw new DockerException("Unexpected response from server: [code=" + ex.getResponse().getStatus() + "]", ex);
            }
        }

        // Check if the response contains an error message
        if (response.isError()) {
            if (response.getErrorDetail().getCode() == 404) {
                throw new ImageNotFoundException(format("Image %s does not exist.", request.getImage()));
            } else {
                throw new DockerException(response.getMessage());
            }
        }
    }

    /**
     * Pull an image from the registry so it is available locally.
     * <p/>
     * This method blocks until the image is downloaded. As such, it may take a long time to
     * complete.
     *
     * @param image name of the image to pull
     * @throws DockerException        if the server reports an error
     * @throws ImageNotFoundException if the requested image does not exist in the registry
     * @throws ClientHandlerException if an error occurs sending the request or receiving the response
     *                                (i.e. server is not listing on specified port, etc)
     */
    public void pullImage(String image) throws DockerException {
        if (image == null) {
            throw new NullPointerException("image");
        }

        pullImage(new PullImageRequest().withImage(image), ProgressMonitor.NULL);
    }

    /**
     * Pull an image from the registry so it is available locally.
     * <p/>
     * This method blocks until the image is downloaded. As such, it may take a long time to
     * complete.
     *
     * @param image    name of the image to pull
     * @param progress receive progress messages
     * @throws DockerException        if the server reports an error
     * @throws ImageNotFoundException if the requested image does not exist in the registry
     * @throws ClientHandlerException if an error occurs sending the request or receiving the response
     *                                (i.e. server is not listing on specified port, etc)
     */
    public void pullImage(String image, ProgressMonitor progress) throws DockerException {
        if (image == null) {
            throw new NullPointerException("image");
        }

        pullImage(new PullImageRequest().withImage(image), progress);
    }

    /**
     * Remove an image from the filesystem.
     * <p/>
     * Returns successfully if the image does not exist.
     *
     * @param request remove options
     * @throws DockerException        if the server reports an error
     * @throws ClientHandlerException if an error occurs sending the request or receiving the response
     *                                (i.e. server is not listing on specified port, etc)
     */
    public void removeImage(RemoveImageRequest request) throws DockerException {
        if (request == null) {
            throw new NullPointerException("request");
        }

        if (request.getImage() == null) {
            throw new IllegalArgumentException("request.image is required");
        }

        try {
            api("images/%s", request.getImage()).delete();
        } catch (UniformInterfaceException ex) {
            switch (ex.getResponse().getStatus()) {
                case 404:
                    // Image does not exist.
                    break;
                case 500:
                    throw new DockerException("Server error", ex);
                default:
                    throw new DockerException("Unexpected response from server: [code=" + ex.getResponse().getStatus() + "]", ex);
            }
        }
    }

    /**
     * Remove an image from the filesystem.
     * <p/>
     * Returns successfully if the image does not exist.
     *
     * @param image Name of the image to remove
     * @throws DockerException        if the server reports an error
     * @throws ClientHandlerException if an error occurs sending the request or receiving the response
     *                                (i.e. server is not listing on specified port, etc)
     */
    public void removeImage(String image) throws DockerException {
        if (image == null) {
            throw new NullPointerException("image");
        }

        removeImage(new RemoveImageRequest().withImage(image));
    }

    /**
     * Create a new container.
     * <p/>
     * The source image for the container must exist locally. If it does
     * not exist locally, the image must be pulled from the registry
     * with {@link #pullImage(PullImageRequest)}.
     *
     * @param request container configuration
     * @return response from the server
     * @throws DockerException        if the server reports an error
     * @throws ImageNotFoundException if the requested image does not exist locally and must be pulled
     * @throws ClientHandlerException if an error occurs sending the request or receiving the response
     *                                (i.e. server is not listing on specified port, etc)
     */
    public CreateContainerResponse createContainer(CreateContainerRequest request) throws DockerException {
        if (request == null) {
            throw new NullPointerException("request");
        }

        if (request.getImage() == null) {
            throw new IllegalArgumentException("request.image is required");
        }

        if (request.getCommand().size() == 0) {
            throw new IllegalArgumentException("request.command is required");
        }

        try {
            return api("containers/create").post(CreateContainerResponse.class, request);
        } catch (UniformInterfaceException ex) {
            switch (ex.getResponse().getStatus()) {
                case 404:
                    throw new ImageNotFoundException(format("Image %s does not exist. Be sure to pull the image before creating a container.", request.getImage()), ex);
                case 406:
                    // TODO What does this error really mean?
                    throw new DockerException("Impossible to attach (container not running).", ex);
                case 500:
                    throw new DockerException("Server error", ex);
                default:
                    throw new DockerException("Unexpected response from server: [code=" + ex.getResponse().getStatus() + "]", ex);
            }
        }
    }

    /**
     * Start an existing container.
     *
     * @param request container options
     * @throws DockerException            if the server reports an error
     * @throws ContainerNotFoundException if the container does not exist
     * @throws ClientHandlerException     if an error occurs sending the request or receiving the response
     *                                    (i.e. server is not listing on specified port, etc)
     */
    public void startContainer(StartContainerRequest request) throws DockerException {
        if (request == null) {
            throw new NullPointerException("request");
        }

        if (request.getContainerId() == null) {
            throw new IllegalArgumentException("request.containerId is required");
        }

        try {
            api("containers/%s/start", request.getContainerId()).post(request);
        } catch (UniformInterfaceException ex) {
            switch (ex.getResponse().getStatus()) {
                case 404:
                    throw new ContainerNotFoundException(format("Container %s does not exist.", request.getContainerId()), ex);
                case 500:
                    throw new DockerException("Server error", ex);
                default:
                    throw new DockerException("Unexpected response from server: [code=" + ex.getResponse().getStatus() + "]", ex);
            }
        }
    }

    /**
     * Start an existing container.
     *
     * @param containerId ID of the container to start
     * @throws DockerException            if the server reports an error
     * @throws ContainerNotFoundException if the container does not exist
     * @throws ClientHandlerException     if an error occurs sending the request or receiving the response
     *                                    (i.e. server is not listing on specified port, etc)
     */
    public void startContainer(String containerId) throws DockerException {
        if (containerId == null) {
            throw new NullPointerException("containerId");
        }

        startContainer(new StartContainerRequest().withContainerId(containerId));
    }

    /**
     * Create a new image from a container's changes.
     *
     * @param request commit options
     * @throws DockerException            if the server reports an error
     * @throws ContainerNotFoundException if the container does not exist
     * @throws ClientHandlerException     if an error occurs sending the request or receiving the response
     *                                    (i.e. server is not listing on specified port, etc)
     */
    public CommitContainerResponse commitContainer(CommitContainerRequest request) throws DockerException {
        if (request == null) {
            throw new NullPointerException("request");
        }

        if (request.getContainerId() == null) {
            throw new IllegalArgumentException("request.containerId is required");
        }
        try {
            WebResource resource = resource("commit");

            MultivaluedMap<String, String> params = new MultivaluedMapImpl();
            params.add("container", request.getContainerId());

            if (request.getRepository().length() > 0) {
                params.add("repo", request.getRepository());
            }

            if (request.getTag().length() > 0) {
                params.add("tag", request.getTag());
            }

            if (request.getMessage().length() > 0) {
                params.add("m", request.getMessage());
            }

            if (request.getAuthor().length() > 0) {
                params.add("author", request.getAuthor());
            }

            return json(resource.queryParams(params)).post(CommitContainerResponse.class);
        } catch (UniformInterfaceException ex) {
            switch (ex.getResponse().getStatus()) {
                case 404:
                    throw new ContainerNotFoundException(format("Container %s does not exist.", request.getContainerId()), ex);
                case 500:
                    throw new DockerException("Server error", ex);
                default:
                    throw new DockerException("Unexpected response from server: [code=" + ex.getResponse().getStatus() + "]", ex);
            }
        }
    }

    /**
     * Block until the specified container stops.
     *
     * @param request wait options
     * @throws DockerException            if the server reports an error
     * @throws ContainerNotFoundException if the container does not exist
     * @throws ClientHandlerException     if an error occurs sending the request or receiving the response
     *                                    (i.e. server is not listing on specified port, etc)
     */
    public WaitContainerResponse waitContainer(WaitContainerRequest request) throws DockerException {
        if (request == null) {
            throw new NullPointerException("request");
        }

        if (request.getContainerId() == null) {
            throw new IllegalArgumentException("request.containerId is required");
        }

        try {
            return api("containers/%s/wait", request.getContainerId()).post(WaitContainerResponse.class);
        } catch (UniformInterfaceException ex) {
            switch (ex.getResponse().getStatus()) {
                case 404:
                    throw new ContainerNotFoundException(format("Container %s does not exist.", request.getContainerId()), ex);
                case 500:
                    throw new DockerException("Server error", ex);
                default:
                    throw new DockerException("Unexpected response from server: [code=" + ex.getResponse().getStatus() + "]", ex);
            }
        }
    }

    /**
     * Block until the specified container stops.
     * <p/>
     * Returns successfully if the container has already stopped.
     *
     * @param containerId ID of the container to wait for
     * @throws DockerException            if the server reports an error
     * @throws ContainerNotFoundException if the container does not exist
     * @throws ClientHandlerException     if an error occurs sending the request or receiving the response
     *                                    (i.e. server is not listing on specified port, etc)
     */
    public WaitContainerResponse waitContainer(String containerId) throws DockerException {
        if (containerId == null) {
            throw new NullPointerException("containerId");
        }

        return waitContainer(new WaitContainerRequest().withContainerId(containerId));
    }

    /**
     * Remove a container from the filesystem.
     * <p/>
     * Returns successfully if the container does not exist.
     *
     * @param request remove options
     * @throws DockerException        if the server reports an error
     * @throws ClientHandlerException if an error occurs sending the request or receiving the response
     *                                (i.e. server is not listing on specified port, etc)
     */
    public void removeContainer(RemoveContainerRequest request) throws DockerException {
        if (request == null) {
            throw new NullPointerException("request");
        }

        if (request.getContainerId() == null) {
            throw new IllegalArgumentException("request.containerId is required");
        }

        try {
            api("containers/%s", request.getContainerId()).delete();
        } catch (UniformInterfaceException ex) {
            switch (ex.getResponse().getStatus()) {
                case 404:
                    // Container does not exist already.
                    break;
                case 500:
                    throw new DockerException("Server error", ex);
                default:
                    throw new DockerException("Unexpected response from server: [code=" + ex.getResponse().getStatus() + "]", ex);
            }
        }
    }

    /**
     * Remove a container from the filesystem.
     * <p/>
     * Returns successfully if the container does not exist.
     *
     * @param containerId ID of the container to remove
     * @throws DockerException        if the server reports an error
     * @throws ClientHandlerException if an error occurs sending the request or receiving the response
     *                                (i.e. server is not listing on specified port, etc)
     */
    public void removeContainer(String containerId) throws DockerException {
        if (containerId == null) {
            throw new NullPointerException("containerId");
        }

        removeContainer(new RemoveContainerRequest().withContainerId(containerId));
    }

    /**
     * Kill a container.
     *
     * @param request kill options
     * @throws DockerException            if the server reports an error
     * @throws ContainerNotFoundException if the container does not exist
     * @throws ClientHandlerException     if an error occurs sending the request or receiving the response
     *                                    (i.e. server is not listing on specified port, etc)
     */
    public void killContainer(KillContainerRequest request) throws DockerException {
        if (request == null) {
            throw new NullPointerException("request");
        }

        if (request.getContainerId() == null) {
            throw new IllegalArgumentException("request.containerId is required");
        }

        try {
            api("containers/%s/kill", request.getContainerId()).post();
        } catch (UniformInterfaceException ex) {
            switch (ex.getResponse().getStatus()) {
                case 404:
                    throw new ContainerNotFoundException(format("Container %s does not exist.", request.getContainerId()), ex);
                case 500:
                    throw new DockerException("Server error", ex);
                default:
                    throw new DockerException("Unexpected response from server: [code=" + ex.getResponse().getStatus() + "]", ex);
            }
        }
    }

    /**
     * Attach to the container to receive stdout and stderr.
     *
     * @param request options for receiving container output
     * @return response content
     * @throws DockerException            if the server reports an error
     * @throws ContainerNotFoundException if the container does not exist
     * @throws ClientHandlerException     if an error occurs sending the request or receiving the response
     *                                    (i.e. server is not listing on specified port, etc)
     */
    public AttachResponse attachContainer(AttachRequest request) throws DockerException {
        if (request == null) {
            throw new NullPointerException("request");
        }

        if (request.getContainerId() == null) {
            throw new IllegalArgumentException("request.containerId is required");
        }

        if ((!request.isStderrIncluded() && !request.isStdoutIncluded()) || (!request.isLogs() && !request.isStream())) {
            return new AttachResponse();
        }

        MultivaluedMap<String, String> params = new MultivaluedMapImpl();
        AttachResponse response = new AttachResponse();

        if (request.isLogs()) {
            params.add("logs", "true");
        }

        if (request.isStream()) {
            params.add("stream", "true");
        }

        // TODO demultiplex both stdout and stderr over single connection

        if (request.isStderrIncluded()) {
            response.setStderr(attachStream(request.getContainerId(), params, STDERR_STREAM, "stderr"));
        }

        if (request.isStdoutIncluded()) {
            response.setStdout(attachStream(request.getContainerId(), params, STDOUT_STREAM, "stdout"));
        }

        return response;
    }

    private InputStream attachStream(String containerId, MultivaluedMap<String, String> params, int streamNum, String streamName) throws DockerException {
        ClientResponse response = resource("containers/%s/attach", containerId)
                .queryParams(params)
                .queryParam(streamName, "true")
                .accept(APPLICATION_DOCKER_RAW_STREAM_TYPE)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .post(ClientResponse.class);

        switch (response.getStatus()) {
            case 200:
                return new AttachStream(streamNum, response.getEntityInputStream());

            case 404:
                throw new ContainerNotFoundException(format("Container %s does not exist.", containerId));
            case 500:
                throw new DockerException("Server error");
            default:
                throw new DockerException("Unexpected response from server: [code=" + response.getStatus() + "]");
        }
    }

    /**
     * Attach to a container to receive stdout and stderr.
     *
     * @param containerId ID of the container to attach to
     * @return response content
     * @throws DockerException            if the server reports an error
     * @throws ContainerNotFoundException if the container does not exist
     * @throws ClientHandlerException     if an error occurs sending the request or receiving the response
     *                                    (i.e. server is not listing on specified port, etc)
     */
    public AttachResponse attachContainer(String containerId) throws DockerException {
        if (containerId == null) {
            throw new NullPointerException("containerId");
        }

        // TODO validate containerId

        return attachContainer(new AttachRequest().withContainerId(containerId));
    }

    /**
     * Kill a container.
     * <p/>
     * Sends SIGKILL.
     *
     * @param containerId ID of the container to kill
     * @throws DockerException            if the server reports an error
     * @throws ContainerNotFoundException if the container does not exist
     * @throws ClientHandlerException     if an error occurs sending the request or receiving the response
     *                                    (i.e. server is not listing on specified port, etc)
     */
    public void killContainer(String containerId) throws DockerException {
        if (containerId == null) {
            throw new NullPointerException("containerId");
        }

        killContainer(new KillContainerRequest().withContainerId(containerId));
    }

    /**
     * Remove a container from the filesystem.
     * <p/>
     * This method blocks until the container stops.
     * <p/>
     * Send SIGTERM, and then SIGKILL after timeout. If the timeout is 0,
     * SIGTERM is sent and this method blocks until the container stops
     * (SIGKILL is not sent).
     *
     * @param request remove options
     * @throws DockerException            if the server reports an error
     * @throws ContainerNotFoundException if the container does not exist
     * @throws ClientHandlerException     if an error occurs sending the request or receiving the response
     *                                    (i.e. server is not listing on specified port, etc)
     */
    public void stopContainer(StopContainerRequest request) throws DockerException {
        if (request == null) {
            throw new NullPointerException("request");
        }

        if (request.getContainerId() == null) {
            throw new IllegalArgumentException("request.containerId is required");
        }

        try {
            WebResource resource = resource("containers/%s/stop", request.getContainerId());

            if (request.getTimeoutSeconds() != 0) {
                resource.queryParam("t", Integer.toString(request.getTimeoutSeconds()));
            }

            json(resource).post();
        } catch (UniformInterfaceException ex) {
            switch (ex.getResponse().getStatus()) {
                case 404:
                    throw new ContainerNotFoundException(format("Container %s does not exist.", request.getContainerId()), ex);
                case 500:
                    throw new DockerException("Server error", ex);
                default:
                    throw new DockerException("Unexpected response from server: [code=" + ex.getResponse().getStatus() + "]", ex);
            }
        }
    }

    private ProgressMessage readLastResponse(ClientResponse clientResponse, ProgressMonitor progress) throws IOException {
        JsonParser jsonParser = new ObjectMapper().getFactory().createParser(clientResponse.getEntityInputStream());
        JsonToken jsonToken;
        ProgressMessage responseObject = null;

        while ((jsonToken = jsonParser.nextToken()) != null) {
            switch (jsonToken) {
                case START_OBJECT:
                    responseObject = jsonParser.readValueAs(ProgressMessage.class);
                    progress.progress(responseObject);
                    break;

                default:
                    // Ignore all other token types
                    break;
            }
        }

        return responseObject;
    }

    private URI uri(String path) {
        return UriBuilder.fromUri(_apiEndpoint).path(path).build();
    }

    private WebResource resource(String path, Object... args) {
        return _httpClient.resource(UriBuilder.fromUri(_apiEndpoint).path(format(path, args)).build());
    }

    private WebResource.Builder json(WebResource resource) {
        return resource
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .type(MediaType.APPLICATION_JSON_TYPE);
    }

    private WebResource.Builder api(String path, Object... args) {
        return json(resource(path, args));
    }

    private static class AttachStream extends InputStream {
        private final int _streamNum;
        private final InputStream _dataStream;
        private final byte[] _headerBuf = new byte[8];

        private boolean _streamClosed;
        private boolean _endOfStream;
        private long _messageLen;

        public AttachStream(int streamNum, InputStream dataStream) {
            _streamNum = streamNum;
            _dataStream = dataStream;
        }

        @Override
        public int read() throws IOException {
            ensureBuf();

            if (_endOfStream) {
                return -1;
            }

            int value = _dataStream.read();

            if (value < 0) {
                _endOfStream = true;
                return -1;
            }

            _messageLen -= 1;
            return value;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            ensureBuf();

            if (_endOfStream) {
                return -1;
            }

            int readLen = (int) Math.min(_messageLen, len);
            int readCount = _dataStream.read(b, off, readLen);

            if (readCount < 0) {
                _endOfStream = true;
                return -1;
            }

            _messageLen -= readCount;
            return readCount;
        }

        @Override
        public void close() throws IOException {
            _streamClosed = true;
            _dataStream.close();
        }

        private void ensureBuf() throws IOException {
            if (_streamClosed) {
                throw new IOException("Stream closed");
            }

            while (!_endOfStream && _messageLen == 0) {
                // read 8 bytes header
                // header is [TYPE, 0, 0, 0, SIZE, SIZE, SIZE, SIZE]
                // TYPE is 1:stdout, 2:stderr
                // SIZE is 4-byte, unsigned, big endian length of message payload

                int count = 0;

                while (count < 8) {
                    int result = _dataStream.read(_headerBuf, count, 8 - count);

                    if (result < 0) {
                        _endOfStream = true;
                        return;
                    }

                    count += result;
                }

                if (_headerBuf[0] != _streamNum || _headerBuf[1] != 0 || _headerBuf[2] != 0 || _headerBuf[3] != 0) {
                    throw new IOException("Unexpected stream header content.");
                }

                // Clear the type because ByteBuffer needs 8 bytes to read the long
                _headerBuf[0] = 0;
                ByteBuffer buffer = ByteBuffer.wrap(_headerBuf);
                buffer.order(ByteOrder.BIG_ENDIAN);
                _messageLen = buffer.getLong();
            }
        }
    }
}
