package net.dump247.docker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;

class Utils {
    public static String encodeWithZ(String value, Pattern invalidChars) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        if (invalidChars == null) {
            throw new NullPointerException("invalidChars");
        }

        Matcher invalidMatch = invalidChars.matcher(value);

        if (invalidMatch.find()) {
            if (invalidMatch.end() - invalidMatch.start() > 1) {
                throw new IllegalArgumentException("invalidChars must match single char");
            }

            StringBuffer buffer = new StringBuffer(value.length() * 2);
            invalidMatch.appendReplacement(buffer, encodeChar(value.charAt(invalidMatch.start())));

            while (invalidMatch.find()) {
                if (invalidMatch.end() - invalidMatch.start() > 1) {
                    throw new IllegalArgumentException("invalidChars must match single char");
                }

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
