package net.dump247.docker;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;
import com.sun.jersey.client.urlconnection.HTTPSProperties;
import com.sun.jersey.core.util.MultivaluedMapImpl;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.String.format;

/**
 * Client for interacting with docker.
 * <p/>
 * Only supports the docker API via TCP. You must bind docker to a TCP
 * port. For example: docker -H 127.0.0.1:2375
 */
public class DockerClient {
    /**
     * Version of the docker API this client uses ({@value}).
     */
    public static final String API_VERSION = "v1.8";

    /**
     * URI of the default local docker API endpoint (http://localhost:2375).
     */
    public static final URI DEFAULT_LOCAL_URI = URI.create("http://localhost:2375");

    public static final String APPLICATION_DOCKER_RAW_STREAM = "application/vnd.docker.raw-stream";
    public static final MediaType APPLICATION_DOCKER_RAW_STREAM_TYPE = MediaType.valueOf(APPLICATION_DOCKER_RAW_STREAM);

    private static final int STDOUT_STREAM = 1;
    private static final int STDERR_STREAM = 2;

    private final URI _apiEndpoint;
    private final Client _httpClient;
    private final SSLContext _sslContext;

    public DockerClient(URI dockerEndpoint) {
        this(dockerEndpoint, null, null, null, null);
    }

    public DockerClient(URI dockerEndpoint, SSLContext sslContext, HostnameVerifier hostnameVerifier, String username, String password) {
        _apiEndpoint = UriBuilder.fromUri(dockerEndpoint).path(API_VERSION).build();
        _httpClient = Client.create();
        _sslContext = sslContext;

        if (!"http".equals(dockerEndpoint.getScheme()) && !"https".equals(dockerEndpoint.getScheme())) {
            throw new IllegalArgumentException(format("Unsupported endpoint scheme. Only http and https are supported: [dockerEndpoint=%s]", dockerEndpoint));
        }

        _httpClient.getProperties().put(ClientConfig.PROPERTY_CONNECT_TIMEOUT, (int) TimeUnit.SECONDS.toMillis(5));
        _httpClient.getProperties().put(ClientConfig.PROPERTY_READ_TIMEOUT, (int) TimeUnit.SECONDS.toMillis(30));

        if (sslContext == null && !"http".equals(dockerEndpoint.getScheme())) {
            // Attach currently directly uses sockets, so we need to ensure the jersey client and
            // the raw sockets implementation uses the same settings. This check may be overly
            // conservative and unnecessary.
            throw new IllegalArgumentException(format("SSL context is required for HTTPS connections: [dockerEndpoint=%s]", dockerEndpoint));
        } else if (sslContext != null) {
            _httpClient.getProperties().put(HTTPSProperties.PROPERTY_HTTPS_PROPERTIES, new HTTPSProperties(hostnameVerifier, sslContext));
        }


        username = username == null ? "" : username;
        password = password == null ? "" : password;

        if (username.length() > 0 || password.length() > 0) {
            _httpClient.addFilter(new HTTPBasicAuthFilter(username, password));
        }
    }

    public URI getEndpoint() {
        return _apiEndpoint;
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
        return new DockerClient(DEFAULT_LOCAL_URI, null, null, null, null);
    }

    public InspectImageResponse inspectImage(String imageName) throws DockerException {
        return inspectImage(new InspectImageRequest().withImageName(imageName));
    }

    public InspectImageResponse inspectImage(InspectImageRequest request) throws DockerException {
        if (request == null) {
            throw new NullPointerException("request");
        }

        if (request.getImageName() == null || request.getImageName().length() == 0) {
            throw new IllegalArgumentException("imageName is required");
        }

        try {
            return resource("images/%s/json", request.getImageName()).get(InspectImageResponse.class);
        } catch (UniformInterfaceException ex) {
            switch (ex.getResponse().getStatus()) {
                case 404:
                    throw new ImageNotFoundException(format("Image %s does not exist. Be sure to pull the image before creating a container.", request.getImageName()), ex);
                case 500:
                    throw new DockerException("Server error", ex);
                default:
                    throw new DockerException("Unexpected response from server: [code=" + ex.getResponse().getStatus() + "]", ex);
            }
        }
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
        pullImage(request, ProgressListener.NULL);
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
    public void pullImage(PullImageRequest request, ProgressListener progress) throws DockerException {
        if (request == null) {
            throw new NullPointerException("request");
        }

        if (request.getImage() == null) {
            throw new IllegalArgumentException("request.image is required");
        }

        if (progress == null) {
            throw new NullPointerException("progress");
        }

        ProgressEvent response;

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
        switch (response.getCode()) {
            case Ok:
                // Operation was successful
                break;

            case NotFound:
                throw new ImageNotFoundException(format("Image %s does not exist.", request.getImage()));

            default:
                throw new DockerException(response.getStatusMessage());
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

        pullImage(new PullImageRequest().withImage(image), ProgressListener.NULL);
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
    public void pullImage(String image, ProgressListener progress) throws DockerException {
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
            WebResource resource = resource("containers/create");

            if (request.getName().length() > 0) {
                resource = resource.queryParam("name", request.getName());
            }

            return json(resource).post(CreateContainerResponse.class, request);
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

    public InspectContainerResponse inspectContainer(String containerId) throws DockerException {
        return inspectContainer(new InspectContainerRequest().withId(containerId));
    }

    public InspectContainerResponse inspectContainer(InspectContainerRequest request) throws DockerException {
        try {
            return api("containers/%s/json", request.getId()).get(InspectContainerResponse.class);
        } catch (UniformInterfaceException ex) {
            switch (ex.getResponse().getStatus()) {
                case 404:
                    throw new ContainerNotFoundException(format("Container %s does not exist.", request.getId()), ex);
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

    public static class ContainerStreams {
        public final InputStream stdout;
        public final InputStream stderr;
        public final OutputStream stdin;

        public ContainerStreams(InputStream stdout, InputStream stderr, OutputStream stdin) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.stdin = stdin;
        }
    }

    private static String path(URI uri) {
        String path = uri.getRawPath();
        String query = uri.getRawQuery();

        return query == null
                ? path
                : path + "?" + query;
    }

    /**
     * Attach to the input and output streams of a container.
     *
     * @param containerId id of the container to attach to
     * @return container stdin and stdout/stderr
     * @throws DockerException            if the server reports an error
     * @throws ContainerNotFoundException if the container does not exist
     * @throws ClientHandlerException     if an error occurs sending the request or receiving the response
     *                                    (i.e. server is not listing on specified port, etc)
     */
    public ContainerStreams attachContainerStreams(String containerId) throws DockerException {
        if (containerId == null) {
            throw new NullPointerException("containerId");
        }

        try {
            URI uri = UriBuilder.fromUri(_apiEndpoint).path(format("containers/%s/attach", containerId))
                    .queryParam("stream", "1")
                    .queryParam("stdin", "1")
                    .queryParam("stdout", "1")
                    .queryParam("stderr", "1")
                    .build();

            Socket socket = "https".equals(_apiEndpoint.getScheme())
                    ? _sslContext.getSocketFactory().createSocket(_apiEndpoint.getHost(), _apiEndpoint.getPort())
                    : new Socket(_apiEndpoint.getHost(), _apiEndpoint.getPort());


            AtomicInteger shutdown = new AtomicInteger();
            OutputStream socketOut = new DualOutputStream(socket.getOutputStream(), shutdown);

            socketOut.write(("" +
                    format("POST %s HTTP/1.0\r\n", path(uri)) +
                    format("Accept: %s\r\n", APPLICATION_DOCKER_RAW_STREAM) +
                    format("Content-Type: %s\r\n", APPLICATION_DOCKER_RAW_STREAM) +
                    format("User-Agent: DockerClient\r\n") +
                    "\r\n"
            ).getBytes());

            InputStream socketIn = new DualInputStream(socket.getInputStream(), shutdown);

            // TODO better response processing
            int ch;
            int prevChar = 0;

            while ((ch = socketIn.read()) >= 0) {
                if (ch == '\r') {
                    continue;
                }

                if (ch == '\n' && prevChar == ch) {
                    break;
                }

                prevChar = ch;
            }

            MultiplexedStream multiplexedStream = new MultiplexedStream(socketIn);

            return new ContainerStreams(
                    new AttachedStream(STDOUT_STREAM, multiplexedStream),
                    new AttachedStream(STDERR_STREAM, multiplexedStream),
                    socketOut
            );
        } catch (MalformedURLException ex) {
            throw new RuntimeException("Unexpected error", ex);
        } catch (IOException ex) {
            throw new DockerException("Error attaching to container: [containerId=" + containerId + "]", ex);
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
                resource = resource.queryParam("t", Integer.toString(request.getTimeoutSeconds()));
            }

            json(resource).post();
        } catch (UniformInterfaceException ex) {
            switch (ex.getResponse().getStatus()) {
                case 404:
                    throw new ContainerNotFoundException(format("Container %s does not exist.", request.getContainerId()), ex);
                case 304:
                    // The container's status has not changed. The container must already have been stopped.
                    return;
                case 500:
                    throw new DockerException("Server error", ex);
                default:
                    throw new DockerException("Unexpected response from server: [code=" + ex.getResponse().getStatus() + "]", ex);
            }
        }
    }

    public void stopContainer(String containerId) throws DockerException {
        if (containerId == null) {
            throw new NullPointerException("containerId");
        }

        stopContainer(new StopContainerRequest().withContainerId(containerId));
    }

    public ListContainersResponse listContainers() throws DockerException {
        return listContainers(new ListContainersRequest());
    }

    public ListContainersResponse listContainers(ListContainersRequest request) throws DockerException {
        if (request == null) {
            throw new NullPointerException("request");
        }

        try {
            WebResource resource = resource("/containers/json");

            if (request.getLimit() > 0) {
                resource = resource.queryParam("limit", Integer.toString(request.getLimit()));
            }

            // Docker v0.8.0 returns Content-Type = text/plain
            // Jersey expected Content-Type = application/json and raises an exception
            // This will be resolved with next release: https://github.com/dotcloud/docker/issues/3967

            ClientResponse response = json(resource).get(ClientResponse.class);

            if (response.getClientResponseStatus() != ClientResponse.Status.OK) {
                throw new UniformInterfaceException(response);
            }

            List<ContainerInfo> containers = new ObjectMapper().readValue(response.getEntityInputStream(), new TypeReference<List<ContainerInfo>>() {
            });
            return new ListContainersResponse(containers == null ? Collections.<ContainerInfo>emptyList() : containers);
        } catch (IOException ex) {
            throw new DockerException("Server error", ex);
        } catch (UniformInterfaceException ex) {
            throw new DockerException("Server error", ex);
        }
    }

    /**
     * Get system-wide information.
     *
     * @return system info
     * @throws DockerException        if the server reports an error
     * @throws ClientHandlerException if an error occurs sending the request or receiving the response
     *                                (i.e. server is not listing on specified port, etc)
     */
    public SystemInfoResponse info() throws DockerException {
        try {
            return api("info").get(SystemInfoResponse.class);
        } catch (UniformInterfaceException ex) {
            throw new DockerException("Failed to get docker system information: [uri=" + uri("info") + "]", ex);
        }
    }

    private ProgressEvent readLastResponse(ClientResponse clientResponse, ProgressListener progress) throws IOException {
        if (clientResponse.getClientResponseStatus() != ClientResponse.Status.OK) {
            throw new UniformInterfaceException(clientResponse);
        }

        JsonParser jsonParser = new ObjectMapper().getFactory().createParser(clientResponse.getEntityInputStream());
        JsonToken jsonToken;
        ProgressEvent responseObject = null;

        while ((jsonToken = jsonParser.nextToken()) != null) {
            switch (jsonToken) {
                case START_OBJECT:
                    responseObject = jsonParser.readValueAs(ProgressEvent.class);
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

    private static class DualOutputStream extends OutputStream {
        private final OutputStream _wrapped;
        private final AtomicInteger _counter;

        public DualOutputStream(OutputStream wrapped, AtomicInteger counter) {
            _wrapped = wrapped;
            _counter = counter;
        }

        @Override
        public void write(int b) throws IOException {
            _wrapped.write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            _wrapped.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            _wrapped.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            _wrapped.flush();
        }

        @Override
        public void close() throws IOException {
            if (_counter.incrementAndGet() == 2) {
                _wrapped.close();
            }
        }
    }

    private static class DualInputStream extends InputStream {
        private final InputStream _wrapped;
        private final AtomicInteger _counter;

        public DualInputStream(InputStream wrapped, AtomicInteger counter) {
            _wrapped = wrapped;
            _counter = counter;
        }

        @Override
        public int read() throws IOException {
            return _wrapped.read();
        }

        @Override
        public int read(byte[] b) throws IOException {
            return _wrapped.read(b);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return _wrapped.read(b, off, len);
        }

        @Override
        public long skip(long n) throws IOException {
            return _wrapped.skip(n);
        }

        @Override
        public int available() throws IOException {
            return _wrapped.available();
        }

        @Override
        public void close() throws IOException {
            if (_counter.incrementAndGet() == 2) {
                _wrapped.close();
            }
        }

        @Override
        public synchronized void mark(int readlimit) {
            _wrapped.mark(readlimit);
        }

        @Override
        public synchronized void reset() throws IOException {
            _wrapped.reset();
        }

        @Override
        public boolean markSupported() {
            return _wrapped.markSupported();
        }
    }

    private static class MultiplexedStream {
        private final InputStream _dataStream;
        private final byte[] _headerBuf = new byte[8];

        private boolean _endOfStream;
        private int _streamClosed;
        private int _streamNum;
        private long _messageLen;

        public MultiplexedStream(InputStream dataStream) {
            _dataStream = dataStream;
        }

        public synchronized int read(final int streamNum, final byte[] bytes, final int off, final int len) throws IOException {
            try {
                readHeader(streamNum);

                if (_endOfStream) {
                    return -1;
                }

                int readLen = (int) Math.min(_messageLen, len);
                int readCount = _dataStream.read(bytes, off, readLen);

                if (readCount < 0) {
                    this.endOfStream();
                    return -1;
                }

                _messageLen -= readCount;
                return readCount;
            } catch (IOException ex) {
                endOfStream();
                throw ex;
            }
        }

        public synchronized int read(final int streamNum) throws IOException {
            try {
                readHeader(streamNum);

                if (_endOfStream) {
                    return -1;
                }

                int value = _dataStream.read();

                if (value < 0) {
                    this.endOfStream();
                    return -1;
                }

                _messageLen -= 1;
                return value;
            } catch (IOException ex) {
                endOfStream();
                throw ex;
            }
        }

        public synchronized void close(final int streamNum) throws IOException {
            _streamClosed |= streamNum;

            if (_streamClosed == (STDERR_STREAM | STDOUT_STREAM)) {
                _dataStream.close();
            }
        }

        private void endOfStream() {
            _endOfStream = true;
            this.notify();
        }

        private void readHeader(int streamNum) throws IOException {
            if ((_streamClosed & streamNum) != 0) {
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
                        this.endOfStream();
                        return;
                    }

                    count += result;
                }

                if (_headerBuf[1] != 0 || _headerBuf[2] != 0 || _headerBuf[3] != 0) {
                    throw new IOException("Unexpected stream header content.");
                }

                _streamNum = _headerBuf[0];

                // Clear the type because ByteBuffer needs 8 bytes to read the long
                _headerBuf[0] = 0;
                ByteBuffer buffer = ByteBuffer.wrap(_headerBuf);
                buffer.order(ByteOrder.BIG_ENDIAN);
                _messageLen = buffer.getLong();
            }

            while (!_endOfStream && _streamNum != streamNum) {
                this.notify();

                try {
                    this.wait();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static class AttachedStream extends InputStream {
        private final int _streamNum;
        private final MultiplexedStream _dataStream;

        public AttachedStream(final int streamNum, final MultiplexedStream dataStream) {
            _streamNum = streamNum;
            _dataStream = dataStream;
        }

        @Override
        public int read(final byte[] bytes, final int off, final int len) throws IOException {
            return _dataStream.read(_streamNum, bytes, off, len);
        }

        @Override
        public int read() throws IOException {
            return _dataStream.read(_streamNum);
        }

        @Override
        public void close() throws IOException {
            _dataStream.close(_streamNum);
        }
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

                if ((_headerBuf[0] & _streamNum) == 0 || _headerBuf[1] != 0 || _headerBuf[2] != 0 || _headerBuf[3] != 0) {
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

    private static final HostnameVerifier ALLOW_ALL_HOSTNAMES = new HostnameVerifier() {
        @Override
        public boolean verify(String s, SSLSession sslSession) {
            return true;
        }
    };
}
