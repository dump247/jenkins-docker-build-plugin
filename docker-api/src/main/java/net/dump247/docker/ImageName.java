package net.dump247.docker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;

public class ImageName {
    public static final String DEFAULT_TAG = "latest";

    private static final Pattern TAG_PATTERN;
    private static final Pattern REPO_PATTERN;
    private static final Pattern IMAGE_NAME_PATTERN;
    private static final Pattern ENCODE_TAG_PATTERN;

    static {
        final String repoPat = "" +
                // Custom registry hostname
                "(?:(?:[a-z0-9_-]+:[0-9]+|[a-z0-9_-]+(?:\\.[a-z0-9_-]+)+(?::[0-9]+)?)/)?" +
                // Repository name
                "[a-z0-9_-]+(?:/[a-z0-9_-]+)?";
        final String tagChars = "[A-Za-z0-9_.-]+";
        final String encodeTagChar = "[^A-Ya-y0-9_.-]"; // invert tagChars, z/Z are escape characters

        REPO_PATTERN = Pattern.compile("^" + repoPat + "$");
        TAG_PATTERN = Pattern.compile("^" + tagChars + "$");
        IMAGE_NAME_PATTERN = Pattern.compile("^(" + repoPat + ")(:" + tagChars + ")?$");
        ENCODE_TAG_PATTERN = Pattern.compile(encodeTagChar);
    }

    private final String _repository;
    private final String _tag;

    public ImageName(String repository) {
        this(repository, DEFAULT_TAG);
    }

    public ImageName(String repository, String tag) {
        _repository = validate("repository", repository, REPO_PATTERN);
        _tag = tag == DEFAULT_TAG
                ? tag
                : validate("tag", tag, TAG_PATTERN);
    }

    public String getRepository() {
        return _repository;
    }

    public String getTag() {
        return _tag;
    }

    @Override
    public String toString() {
        return _repository + ":" + _tag;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ImageName)) {
            return false;
        } else if (obj == this) {
            return true;
        }

        ImageName other = (ImageName) obj;
        return _repository.equals(other._repository) &&
                _tag.equals(other._tag);
    }

    @Override
    public int hashCode() {
        int hash = 98324983;
        hash = (31 * hash) + _repository.hashCode();
        hash = (31 * hash) + _tag.hashCode();
        return hash;
    }

    private static String validate(String name, String value, Pattern pattern) {
        if (value == null) {
            throw new NullPointerException(name);
        }

        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + pattern.pattern() + ": value=" + value);
        }

        return value;
    }

    /**
     * Parse an image name into a repository name and tag. If no tag name is included in the value,
     * {@link #DEFAULT_TAG} is used.
     *
     * @throws NullPointerException     if <code>value</code> is null
     * @throws IllegalArgumentException if <code>value</code> is not a valid image name
     */
    public static ImageName parse(String value) {
        if (value == null) {
            throw new NullPointerException("imageName");
        }

        Matcher result = IMAGE_NAME_PATTERN.matcher(value);

        if (!result.matches()) {
            throw new IllegalArgumentException("Invalid image name: " + value);
        }

        String repository = result.group(1);
        String tag = result.group(2);

        if (tag == null) {
            tag = DEFAULT_TAG;
        } else {
            tag = tag.substring(1); // remove ':' prefix char
        }

        return new ImageName(repository, tag);
    }

    /**
     * Encode a string in such a way that it will be a valid image tag name.
     * <p/>
     * The output is guaranteed to be consistent for the same input value. The output is guaranteed
     * to be different for different input values. Characters that are not valid in a tag name are
     * replaced with valid sequences.
     *
     * @throws NullPointerException     if <code>value</code> is null
     * @throws IllegalArgumentException if <code>value</code> is empty
     */
    public static String encodeTag(String value) {
        if (value == null) {
            throw new NullPointerException("value");
        } else if (value.length() == 0) {
            throw new IllegalArgumentException("empty value not allowed");
        }

        Matcher invalidMatch = ENCODE_TAG_PATTERN.matcher(value);

        if (invalidMatch.find()) {
            StringBuffer buffer = new StringBuffer(value.length() * 2);
            invalidMatch.appendReplacement(buffer, encodeChar(value.charAt(invalidMatch.start())));

            while (invalidMatch.find()) {
                invalidMatch.appendReplacement(buffer, encodeChar(value.charAt(invalidMatch.start())));
            }

            invalidMatch.appendTail(buffer);
            value = buffer.toString();
        }

        return value;
    }

    private static String encodeChar(char ch) {
        if (ch == 'z') {
            return "zz";
        } else if (ch == 'Z') {
            return "ZZ";
        }

        if (ch <= 0xFF) {
            return format("z%02X", (int) ch);
        } else {
            return format("Z%04X", (int) ch);
        }
    }
}
