package limn.backend.lwjgl.transfer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Files as the {@code text/uri-list} of RFC 2483: one {@code file:} URI a line, CRLF between. */
public final class UriLists {

    private UriLists() {
    }

    /**
     * @param files the files
     * @return their absolute {@code file:} URIs, percent-encoded, each ended by CRLF
     */
    public static byte[] encode(List<Path> files) {
        StringBuilder out = new StringBuilder();
        for (Path file : files) {
            String uri = file.toAbsolutePath().toUri().toASCIIString();
            // Path.toUri marks a directory it can see with a trailing slash; a list names the
            // entry, as a file manager writes it.
            if (uri.endsWith("/") && uri.length() > "file:///".length()) {
                uri = uri.substring(0, uri.length() - 1);
            }
            out.append(uri).append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * @param bytes a URI list
     * @return the local files it names; comments, other schemes and what is not a URI are skipped
     */
    public static List<Path> decode(byte[] bytes) {
        List<Path> files = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\r?\\n|\\r")) {
            String text = line.strip();
            int nul = text.indexOf('\0');
            if (nul >= 0) {
                text = text.substring(0, nul);
            }
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            try {
                URI uri = new URI(text);
                if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.getPath() == null) {
                    continue;
                }
                // file://host/path names this machine by its host name on some desktops; the
                // path is what is local.
                files.add(Path.of(new URI("file", null, uri.getPath(), null)));
            } catch (Exception notAFile) {
                // A line that is no URI, or a path this file system cannot hold: not a file here.
            }
        }
        return files;
    }
}
