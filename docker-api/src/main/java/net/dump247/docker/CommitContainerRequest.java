package net.dump247.docker;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** Request to create a new image from a container's changes. */
public class CommitContainerRequest {
    private String _containerId;
    private String _repository = "";
    private String _tag = "";
    private String _message = "";
    private String _author = "";

    /**
     * ID of the container to commit or null if not yet provided.
     *
     * @return container id
     */
    public String getContainerId() {
        return _containerId;
    }

    @JsonIgnore
    public void setContainerId(final String containerId) {
        if (containerId == null) {
            throw new NullPointerException("containerId");
        }

        // TODO validate containerId

        _containerId = containerId;
    }

    public CommitContainerRequest withContainerId(final String containerId) {
        setContainerId(containerId);
        return this;
    }

    /**
     * Repository to commit the image to or empty to use default.
     *
     * @return repository name
     */
    public String getRepository() {
        return _repository;
    }

    @JsonIgnore
    public void setRepository(final String repository) {
        if (repository == null) {
            throw new NullPointerException("repository");
        }

        _repository = repository;
    }

    public CommitContainerRequest withRepository(final String repository) {
        setRepository(repository);
        return this;
    }

    /**
     * Tag to apply to the new image or empty to use the default.
     *
     * @return tag name
     */
    public String getTag() {
        return _tag;
    }

    @JsonIgnore
    public void setTag(final String tag) {
        if (tag == null) {
            throw new NullPointerException("tag");
        }

        _tag = tag;
    }

    public CommitContainerRequest withTag(final String tag) {
        setTag(tag);
        return this;
    }

    /**
     * Commit message or empty for none.
     *
     * @return commit message
     */
    public String getMessage() {
        return _message;
    }

    @JsonIgnore
    public void setMessage(final String message) {
        if (message == null) {
            throw new NullPointerException("message");
        }

        _message = message;
    }

    public CommitContainerRequest withMessage(final String message) {
        setMessage(message);
        return this;
    }

    /**
     * Commit author (eg. "John Hannibal Smith <hannibal@a-team.com>") or empty for none.
     *
     * @return commit author
     */
    public String getAuthor() {
        return _author;
    }

    @JsonIgnore
    public void setAuthor(final String author) {
        if (author == null) {
            throw new NullPointerException("author");
        }

        _author = author;
    }

    public CommitContainerRequest withAuthor(final String author) {
        setAuthor(author);
        return this;
    }
}
