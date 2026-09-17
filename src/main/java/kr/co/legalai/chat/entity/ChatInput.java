package kr.co.legalai.chat.entity;

/** 역할은 서버에서만 지정한다. HTTP 입력으로 역할을 받지 않는다. */
public record ChatInput(String role, String content) {
}
