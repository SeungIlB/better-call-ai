package kr.co.legalai.legaldata.entity;

/** 법령의 형식. 민법 등의 법령명과 housing_lease 등의 주제 분류는 포함하지 않는다. */
public enum LawKind {
    CONSTITUTION,
    ACT,
    PRESIDENTIAL_DECREE,
    PRIME_MINISTER_ORDINANCE,
    MINISTERIAL_ORDINANCE,
    RULE;

    public static LawKind fromOfficialName(String name) {
        if (name == null) return null;
        return switch (name.trim()) {
            case "헌법" -> CONSTITUTION;
            case "법률" -> ACT;
            case "대통령령" -> PRESIDENTIAL_DECREE;
            case "총리령" -> PRIME_MINISTER_ORDINANCE;
            case "부령" -> MINISTERIAL_ORDINANCE;
            case "대법원규칙", "헌법재판소규칙", "중앙선거관리위원회규칙", "국회규칙", "감사원규칙", "규칙" -> RULE;
            default -> null;
        };
    }
}
