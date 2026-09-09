package kr.co.legalai.file.repository;

import jakarta.annotation.PreDestroy;
import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 원본 경로 대신 제한된 바이트 스트림만 전송하며 명확한 OK 응답만 통과시킨다. */
@Repository
public class ClamAvRepository {
    private final InetSocketAddress endpoint;
    private final int timeoutMs;
    private final Semaphore slots = new Semaphore(2);
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("clamav-deadline").factory());

    public ClamAvRepository(@Value("${integrations.clamav.host:127.0.0.1}") String host,
                            @Value("${integrations.clamav.port:3310}") int port,
                            @Value("${integrations.clamav.timeout-ms:10000}") int timeoutMs) {
        if (host.isBlank() || port < 1 || port > 65535 || timeoutMs < 1 || timeoutMs > 30000) {
            throw new IllegalArgumentException("ClamAV 연결 설정을 확인해 주세요.");
        }
        this.endpoint = new InetSocketAddress(host, port);
        this.timeoutMs = timeoutMs;
        deadlines.setRemoveOnCancelPolicy(true);
    }

    public void assertClean(Path path) {
        if (!slots.tryAcquire()) {
            throw new BusinessException(ErrorCode.MALWARE_SCAN_UNAVAILABLE);
        }
        try (var socket = new Socket()) {
            // 읽기 타임아웃만으로는 쓰기 정체를 막을 수 없어 전체 기한에 소켓을 닫는다.
            var deadline = deadlines.schedule(() -> close(socket), timeoutMs, TimeUnit.MILLISECONDS);
            try {
                socket.connect(endpoint, Math.min(2000, timeoutMs));
                socket.setSoTimeout(timeoutMs);
                var output = new DataOutputStream(socket.getOutputStream());
                output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                try (var input = Files.newInputStream(path)) {
                    byte[] chunk = new byte[8192];
                    long total = 0;
                    int size;
                    while ((size = input.read(chunk)) != -1) {
                        total += size;
                        if (total > 20L * 1024 * 1024) {
                            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
                        }
                        output.writeInt(size);
                        output.write(chunk, 0, size);
                    }
                    if (total == 0) {
                        throw new BusinessException(ErrorCode.INVALID_FILE);
                    }
                }
                output.writeInt(0);
                output.flush();
                var reply = new ByteArrayOutputStream();
                var input = socket.getInputStream();
                for (int i = 0; i < 1024; i++) {
                    int next = input.read();
                    if (next == 0) {
                        String result = reply.toString(StandardCharsets.US_ASCII);
                        if (result.equals("stream: OK")) {
                            return;
                        }
                        if (result.startsWith("stream: ") && result.endsWith(" FOUND")) {
                            throw new BusinessException(ErrorCode.UNSAFE_FILE);
                        }
                        break;
                    }
                    if (next < 0) {
                        break;
                    }
                    reply.write(next);
                }
                throw new BusinessException(ErrorCode.MALWARE_SCAN_UNAVAILABLE);
            } finally {
                deadline.cancel(false);
            }
        } catch (IOException exception) {
            // 외부 응답·파일 경로·원문을 예외나 로그로 전달하지 않는다.
            throw new BusinessException(ErrorCode.MALWARE_SCAN_UNAVAILABLE);
        } finally {
            slots.release();
        }
    }

    private void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 요청 스레드가 통신 실패를 처리한다.
        }
    }

    @PreDestroy
    public void shutdown() {
        deadlines.shutdownNow();
    }
}
