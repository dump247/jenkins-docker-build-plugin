package net.dump247.jenkins.plugins.dockerbuild;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.google.common.base.Strings.isNullOrEmpty;

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
}
