package net.dump247.docker;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class ImageNameTest {
    @DataProvider
    public static Object[][] parseData() {
        return new Object[][]{
                {"a", "a", ImageName.DEFAULT_TAG},
                {"a:b", "a", "b"},
                {"a:123", "a", "123"},
                {"z/a", "z/a", ImageName.DEFAULT_TAG},
                {"z/a:b", "z/a", "b"},
                {"host.name/a", "host.name/a", ImageName.DEFAULT_TAG},
                {"host.name/a:tag", "host.name/a", "tag"},
                {"host.name/a/b", "host.name/a/b", ImageName.DEFAULT_TAG},
                {"host.name/a/b:tag", "host.name/a/b", "tag"},
                {"hostname:80/a", "hostname:80/a", ImageName.DEFAULT_TAG},
                {"hostname:80/a:tag", "hostname:80/a", "tag"},
                {"hostname:80/a/b", "hostname:80/a/b", ImageName.DEFAULT_TAG},
                {"hostname:80/a/b:tag", "hostname:80/a/b", "tag"},
                {"host.name:80/a", "host.name:80/a", ImageName.DEFAULT_TAG},
                {"host.name:80/a:tag", "host.name:80/a", "tag"},
                {"host.name:80/a/b", "host.name:80/a/b", ImageName.DEFAULT_TAG},
                {"host.name:80/a/b:tag", "host.name:80/a/b", "tag"}
        };
    }

    @Test(dataProvider = "parseData")
    public void parse(String imageName, String expectedRepo, String expectedTag) {
        ImageName name = ImageName.parse(imageName);
        assertEquals(name.getRepository(), expectedRepo);
        assertEquals(name.getTag(), expectedTag);
    }

    @DataProvider
    public static Object[][] parse_invalid_namesData() {
        return new Object[][]{
                {"a:b:c"},
                {"a/b/c"},
                {"host.name"},
                {"host."},
                {".name"},
                {"."},
                {"a/b.c"},
                {"host.name/a/b/c"},
                {"hostname:80/a/b/c"},
                {"host.name:80/a/b/c"}
        };
    }

    @Test(dataProvider = "parse_invalid_namesData", expectedExceptions = IllegalArgumentException.class)
    public void parse_invalid_names(String imageName) {
        ImageName.parse(imageName);
    }

    @DataProvider
    public static Object[][] encodeTagData() {
        return new Object[][] {
                {"a", "a"},
                {"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-", "abcdefghijklmnopqrstuvwxyzzABCDEFGHIJKLMNOPQRSTUVWXYZZ0123456789_.-"},
                {"\u0000", "z00"},
                {"a\u0000", "az00"},
                {"\u0000a", "z00a"},
                {"a\u0000a", "az00a"},
                {"a\u00FFa", "azFFa"},
                {"Z\u0100Z", "ZZZ0100ZZ"},
                {"Z\uFFFFZ", "ZZZFFFFZZ"}
        };
    }

    @Test(dataProvider = "encodeTagData")
    public void encodeTag(String value, String expectedTag) {
        assertEquals(ImageName.encodeTag(value), expectedTag);
    }
}
