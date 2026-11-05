package dev.gitdriven.core;

import java.util.List;
import java.util.regex.Pattern;

/** Simple globs over git paths (always with "/"): {@code *}, {@code **} and {@code ?}. */
final class PathGlob {
    private final List<Pattern> patterns;

    PathGlob(List<String> globs) {
        this.patterns = globs.stream().map(PathGlob::compile).toList();
    }

    boolean matches(String path) {
        return patterns.stream().anyMatch(p -> p.matcher(path).matches());
    }

    private static Pattern compile(String glob) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    re.append(".*");
                    i++;
                } else {
                    re.append("[^/]*");
                }
            } else if (ch == '?') {
                re.append("[^/]");
            } else {
                re.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(re.toString());
    }
}
