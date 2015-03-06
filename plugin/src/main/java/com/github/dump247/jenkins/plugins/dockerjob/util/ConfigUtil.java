package com.github.dump247.jenkins.plugins.dockerjob.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;

public class ConfigUtil {
    public static List<ConfigLine> splitConfigLines(String content) {
        if (isNullOrEmpty(content)) {
            return ImmutableList.of();
        }

        String[] lines = content.split("[\r\n]+");
        ImmutableList.Builder<ConfigLine> output = ImmutableList.builder();
        int lineNum = 0;

        for (String line : lines) {
            lineNum += 1;

            line = line.trim();

            if (!line.isEmpty() && line.charAt(0) != '#') {
                output.add(new ConfigLine(lineNum, line));
            }
        }

        return output.build();
    }

    public static class ConfigLine {
        public final int lineNum;
        public final String value;

        public ConfigLine(int lineNum, String value) {
            this.lineNum = lineNum;
            this.value = value;
        }
    }

    public static Map<String, String> parseEnvVars(String content) {
        ImmutableMap.Builder<String, String> vars = ImmutableMap.builder();

        for (ConfigUtil.ConfigLine line : splitConfigLines(content)) {
            String[] parts = line.value.split("=", 2);
            String name = parts[0].trim();
            String value = parts.length > 1 ? parts[1].trim() : "";

            if (!name.matches("^[a-zA-Z_][a-zA-Z_0-9]*$")) {
                throw new IllegalArgumentException(format("Environment variable name is invalid (line %d): %s", line.lineNum, line));
            }

            vars.put(name, value);
        }

        return vars.build();
    }
}
