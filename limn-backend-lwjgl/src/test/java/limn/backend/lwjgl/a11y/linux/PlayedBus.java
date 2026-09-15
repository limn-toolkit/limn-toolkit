package limn.backend.lwjgl.a11y.linux;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A D-Bus bus played by a test over a real unix socket: the SASL server's half of the handshake,
 * and whole messages written and read the way a bus relays them. No daemon is involved.
 */
final class PlayedBus {

    private PlayedBus() {
    }

    /** The bus's end of one client connection. */
    static final class Peer {
        final SocketChannel channel;
        final List<String> handshake = new ArrayList<>();

        Peer(SocketChannel channel) {
            this.channel = channel;
        }

        /** Plays the SASL server: the NUL, then every line up to BEGIN, answering AUTH with OK. */
        void authenticate() throws IOException {
            ByteBuffer nul = ByteBuffer.allocate(1);
            readFully(nul);
            while (true) {
                String line = readLine();
                handshake.add(line);
                if (line.startsWith("AUTH ")) {
                    write(("OK 0123456789abcdef0123456789abcdef\r\n").getBytes(StandardCharsets.US_ASCII));
                } else if (line.startsWith("NEGOTIATE_UNIX_FD")) {
                    write("AGREE_UNIX_FD\r\n".getBytes(StandardCharsets.US_ASCII));
                } else if (line.equals("BEGIN")) {
                    return;
                }
            }
        }

        /** Plays a SASL server that refuses: the NUL, the AUTH line, then REJECTED. */
        void refuse() throws IOException {
            ByteBuffer nul = ByteBuffer.allocate(1);
            readFully(nul);
            handshake.add(readLine());
            write("REJECTED EXTERNAL\r\n".getBytes(StandardCharsets.US_ASCII));
        }

        String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            ByteBuffer one = ByteBuffer.allocate(1);
            while (true) {
                one.clear();
                readFully(one);
                char c = (char) (one.get(0) & 0xff);
                if (c == '\n') {
                    return sb.toString().replace("\r", "");
                }
                sb.append(c);
            }
        }

        void write(byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }

        void readFully(ByteBuffer buffer) throws IOException {
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException("the client hung up");
                }
            }
        }

        DBus.Msg readMessage() throws IOException {
            ByteBuffer head = ByteBuffer.allocate(16);
            readFully(head);
            head.flip().order(ByteOrder.LITTLE_ENDIAN);
            int body = head.getInt(4);
            int fields = (head.getInt(12) + 7) & ~7;
            byte[] full = new byte[16 + fields + body];
            System.arraycopy(head.array(), 0, full, 0, 16);
            readFully(ByteBuffer.wrap(full, 16, full.length - 16));
            return DBus.Msg.parse(full);
        }
    }

}
