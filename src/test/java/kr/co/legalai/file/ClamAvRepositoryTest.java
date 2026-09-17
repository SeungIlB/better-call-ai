package kr.co.legalai.file;

import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.file.repository.ClamAvRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ClamAvRepositoryTest {
    @TempDir
    Path root;

    @Test
    void sendsFramedChunksAndAcceptsOnlyCleanReply() throws Exception {
        checkReply("stream: OK\0", null);
    }

    @Test
    void detectedFileIsRejected() throws Exception {
        checkReply("stream: Test-Signature FOUND\0", ErrorCode.UNSAFE_FILE);
    }

    @Test
    void errorsTruncationAndOversizedRepliesNeverPass() throws Exception {
        for (String reply : new String[]{"stream: Access denied ERROR\0", "stream: OK", "OK\0",
                "stream: OK extra\0", "x".repeat(1024)}) {
            checkReply(reply, ErrorCode.MALWARE_SCAN_UNAVAILABLE);
        }
    }

    @Test
    void unavailableServerFailsWithoutRetry() throws Exception {
        int port;
        try (var server = new ServerSocket(0)) {
            port = server.getLocalPort();
        }
        var client = new ClamAvRepository("127.0.0.1", port, 500);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertEquals(ErrorCode.MALWARE_SCAN_UNAVAILABLE,
                            assertThrows(BusinessException.class,
                                    () -> client.assertClean(root.resolve("unused"))).getErrorCode()));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void stalledResponseIsBoundedByOverallDeadline() throws Exception {
        try (var server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(3000);
            var peer = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    readUpload(new DataInputStream(socket.getInputStream()));
                    assertEquals(-1, socket.getInputStream().read());
                }
                return null;
            });
            var client = new ClamAvRepository("127.0.0.1", server.getLocalPort(), 500);
            try {
                Path file = Files.write(root.resolve("sample"), new byte[]{1});
                assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                        assertEquals(ErrorCode.MALWARE_SCAN_UNAVAILABLE,
                                assertThrows(BusinessException.class, () -> client.assertClean(file)).getErrorCode()));
                peer.get(4, TimeUnit.SECONDS);
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    void stalledWriteAlsoStopsAtDeadline() throws Exception {
        try (var server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(3000);
            var client = new ClamAvRepository("127.0.0.1", server.getLocalPort(), 500);
            try {
                Path file = Files.write(root.resolve("large"), new byte[20 * 1024 * 1024]);
                var outcome = executor.submit(() -> assertThrows(BusinessException.class,
                        () -> client.assertClean(file)).getErrorCode());
                try (var peer = server.accept()) {
                    // 서버가 읽지 않아 전송 버퍼가 가득 차는 상황을 재현한다.
                    assertEquals(ErrorCode.MALWARE_SCAN_UNAVAILABLE, outcome.get(3, TimeUnit.SECONDS));
                }
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    void thirdConcurrentScanIsRejectedImmediately() throws Exception {
        try (var server = new ServerSocket(0); var executor = Executors.newFixedThreadPool(2)) {
            server.setSoTimeout(3000);
            var client = new ClamAvRepository("127.0.0.1", server.getLocalPort(), 2000);
            try {
                Path file = Files.write(root.resolve("sample"), new byte[]{1});
                java.util.concurrent.Callable<ErrorCode> scan = () -> assertThrows(BusinessException.class,
                        () -> client.assertClean(file)).getErrorCode();
                var first = executor.submit(scan);
                var second = executor.submit(scan);
                try (var peer1 = server.accept(); var peer2 = server.accept()) {
                    assertTimeoutPreemptively(Duration.ofMillis(500), () ->
                            assertEquals(ErrorCode.MALWARE_SCAN_UNAVAILABLE, scan.call()));
                }
                assertEquals(ErrorCode.MALWARE_SCAN_UNAVAILABLE, first.get(3, TimeUnit.SECONDS));
                assertEquals(ErrorCode.MALWARE_SCAN_UNAVAILABLE, second.get(3, TimeUnit.SECONDS));
            } finally {
                client.shutdown();
            }
        }
    }

    private void checkReply(String reply, ErrorCode expected) throws Exception {
        byte[] bytes = new byte[20000];
        new java.util.Random(1).nextBytes(bytes);
        Path file = Files.write(root.resolve("sample"), bytes);
        try (var server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(3000);
            var peer = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    assertArrayEquals(bytes, readUpload(new DataInputStream(socket.getInputStream())));
                    socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                }
                return null;
            });
            var client = new ClamAvRepository("127.0.0.1", server.getLocalPort(), 2000);
            try {
                if (expected == null) {
                    assertDoesNotThrow(() -> client.assertClean(file));
                } else {
                    assertEquals(expected, assertThrows(BusinessException.class,
                            () -> client.assertClean(file)).getErrorCode());
                }
                peer.get(4, TimeUnit.SECONDS);
            } finally {
                client.shutdown();
            }
        }
    }

    private byte[] readUpload(DataInputStream input) throws Exception {
        assertEquals("zINSTREAM\0", new String(input.readNBytes(10), StandardCharsets.US_ASCII));
        var bytes = new ByteArrayOutputStream();
        int size;
        while ((size = input.readInt()) != 0) {
            assertTrue(size > 0 && size <= 8192);
            byte[] chunk = input.readNBytes(size);
            assertEquals(size, chunk.length);
            bytes.write(chunk);
        }
        return bytes.toByteArray();
    }
}
