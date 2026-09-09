package kr.co.legalai.chat.service.impl;

import kr.co.legalai.chat.dto.response.ChatTurnResponse;
import kr.co.legalai.chat.entity.ChatInput;
import kr.co.legalai.chat.entity.ChatTurn;
import kr.co.legalai.chat.entity.GeneratedAnswer;
import kr.co.legalai.chat.repository.ChatRepository;
import kr.co.legalai.chat.repository.OpenAiChatRepository;
import kr.co.legalai.chat.service.ChatService;
import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.common.response.PageResponse;
import kr.co.legalai.common.transaction.UserScopedTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatServiceImpl implements ChatService {
    private final UserScopedTransaction transactions;
    private final ChatRepository repository;
    private final OpenAiChatRepository openAi;
    // 상품별 일일 한도가 아니라 인스턴스의 외부 호출 동시 실행 보호다.
    private final Semaphore slots = new Semaphore(2);

    @Override
    public ChatTurnResponse send(UUID caseId, UUID key, String content) {
        if (content == null || content.isBlank() || content.length() > 4000) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return guarded(() -> execute(caseId, key, content.trim(), null));
    }

    @Override
    public ChatTurnResponse retry(UUID caseId, UUID id, int expectedAttempt) {
        if (expectedAttempt < 1) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        return guarded(() -> execute(caseId, id, null, expectedAttempt));
    }

    private ChatTurnResponse execute(UUID caseId, UUID id, String content, Integer retryAttempt) {
        // 예약 커밋 이후에만 외부 API를 호출한다. API 대기 중 DB 연결·행 잠금을 보유하지 않는다.
        Reservation reservation = transactions.execute(userId -> {
            repository.lockOwner(userId);
            repository.lockCase(caseId);
            repository.expireAbandoned();
            var existing = repository.find(caseId, id);
            if (existing.isPresent()) {
                ChatTurn turn = existing.get();
                if (content != null && !content.equals(turn.question())) {
                    throw new BusinessException(ErrorCode.CHAT_KEY_CONFLICT);
                }
                if (retryAttempt != null && retryAttempt > turn.attempt()) {
                    throw new BusinessException(ErrorCode.CHAT_RETRY_CONFLICT);
                }
                if (turn.status().equals("COMPLETED")) return new Reservation(turn, List.of(), false);
                // 실패 응답도 동일 요청 재전송으로 재실행하지 않는다. 명시적인 다음 시도만 허용한다.
                if (turn.status().equals("RUNNING")) return new Reservation(turn, List.of(), false);
                if (retryAttempt == null || retryAttempt < turn.attempt()) {
                    return new Reservation(turn, List.of(), false);
                }
                if (turn.turnNo() != repository.latestNumber(caseId)) {
                    throw new BusinessException(ErrorCode.CHAT_RETRY_CONFLICT);
                }
            } else if (retryAttempt != null) {
                throw new BusinessException(ErrorCode.CHAT_NOT_FOUND);
            }
            openAi.requireConfigured();
            if (repository.hasActiveTurn()) throw new BusinessException(ErrorCode.CHAT_BUSY);
            if (existing.isPresent()) repository.retry(caseId, id);
            else repository.insert(caseId, id, userId, content);
            ChatTurn turn = repository.find(caseId, id).orElseThrow();
            return new Reservation(turn, context(caseId, turn), true);
        });
        ChatTurn turn = reservation.turn();
        if (!reservation.generate()) {
            if (turn.status().equals("COMPLETED")) return turn.response(false);
            throw new BusinessException(turn.status().equals("RUNNING")
                    ? ErrorCode.CHAT_BUSY : ErrorCode.CHAT_GENERATION_FAILED);
        }
        if (!slots.tryAcquire()) {
            markFailed(caseId, turn, ErrorCode.CHAT_BUSY);
            throw new BusinessException(ErrorCode.CHAT_BUSY);
        }
        try {
            log.info("채팅 실행 시작 caseId={} turnId={} attempt={}", caseId, id, turn.attempt());
            GeneratedAnswer answer = openAi.generate(reservation.context());
            // 저장만 한 번 재시도한다. 저장 장애를 이유로 모델을 다시 호출하지 않는다.
            for (int saveAttempt = 0; saveAttempt < 2; saveAttempt++) {
                try {
                    ChatTurnResponse saved = transactions.execute(userId -> {
                        repository.lockCase(caseId);
                        ChatTurn current = repository.find(caseId, id).orElseThrow();
                        if (current.status().equals("COMPLETED")) return current.response(false);
                        if (!repository.complete(caseId, id, turn.attempt(), answer)) {
                            throw new BusinessException(ErrorCode.CHAT_SAVE_FAILED);
                        }
                        return repository.find(caseId, id).orElseThrow().response(false);
                    });
                    log.info("채팅 답변 저장 완료 caseId={} turnId={} attempt={}", caseId, id, saved.attempt());
                    return saved;
                } catch (DataAccessException | TransactionException failure) {
                    if (saveAttempt == 1) throw new BusinessException(ErrorCode.CHAT_SAVE_FAILED);
                }
            }
            throw new BusinessException(ErrorCode.CHAT_SAVE_FAILED);
        } catch (BusinessException failure) {
            markFailed(caseId, turn, failure.getErrorCode());
            throw failure;
        } catch (RuntimeException failure) {
            markFailed(caseId, turn, ErrorCode.CHAT_GENERATION_FAILED);
            throw new BusinessException(ErrorCode.CHAT_GENERATION_FAILED);
        } finally {
            slots.release();
        }
    }

    private List<ChatInput> context(UUID caseId, ChatTurn current) {
        List<ChatTurn> selected = new ArrayList<>();
        int remaining = 16000 - current.question().length();
        for (ChatTurn previous : repository.context(caseId, current.turnNo())) {
            int length = previous.question().length() + previous.answer().length();
            if (length > remaining) break;
            selected.add(previous);
            remaining -= length;
        }
        List<ChatInput> inputs = new ArrayList<>();
        for (ChatTurn previous : selected.reversed()) {
            inputs.add(new ChatInput("user", previous.question()));
            inputs.add(new ChatInput("assistant", previous.answer()));
        }
        inputs.add(new ChatInput("user", current.question()));
        return List.copyOf(inputs);
    }

    private void markFailed(UUID caseId, ChatTurn turn, ErrorCode code) {
        log.warn("채팅 실패 caseId={} turnId={} attempt={} code={}", caseId, turn.id(), turn.attempt(), code.code());
        try {
            transactions.execute(userId -> {
                repository.fail(caseId, turn.id(), turn.attempt(), code.name());
                return true;
            });
        } catch (RuntimeException ignored) {
            // DB 자체 장애라면 다음 접근의 만료 처리로 복구한다. 본문·SQL·예외 원문을 로깅하지 않는다.
            log.warn("채팅 실패 상태 저장 불가 turnId={}", turn.id());
        }
    }

    @Override
    public PageResponse<ChatTurnResponse> list(UUID caseId, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        return guarded(() -> transactions.execute(userId -> {
            repository.lockOwner(userId);
            repository.lockCase(caseId);
            repository.expireAbandoned();
            long latest = repository.latestNumber(caseId);
            var rows = repository.page(caseId, page, pageSize);
            return new PageResponse<>(rows.stream().limit(pageSize)
                    .map(turn -> turn.response(turn.turnNo() == latest)).toList(), page, pageSize, rows.size() > pageSize);
        }));
    }

    private <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException | TransactionException failure) {
            // JDBC 예외에는 상담 본문이 포함될 수 있어 전역 stack trace 로거로 전달하지 않는다.
            throw new BusinessException(ErrorCode.CHAT_SAVE_FAILED);
        }
    }

    private record Reservation(ChatTurn turn, List<ChatInput> context, boolean generate) { }
}
