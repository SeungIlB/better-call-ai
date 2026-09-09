package kr.co.legalai.common.response;

import java.util.List;

/** 전체 건수 조회 없이 다음 페이지 유무를 제공하는 공통 목록 응답. 페이지는 1부터 시작한다. */
public record PageResponse<T>(List<T> items, int page, int pageSize, boolean hasNext) {
    public PageResponse {
        items = List.copyOf(items);
    }
}
