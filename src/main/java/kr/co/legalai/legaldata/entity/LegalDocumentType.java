package kr.co.legalai.legaldata.entity;

/**
 * 국가법령정보 공동활용 API에서 MVP가 사용하는 공식 법률 문서 유형.
 */
public enum LegalDocumentType {
    LAW("law", "LawSearch", "law"),
    PRECEDENT("prec", "PrecSearch", "prec");

    private final String apiTarget;
    private final String searchRoot;
    private final String searchItems;

    LegalDocumentType(String apiTarget, String searchRoot, String searchItems) {
        this.apiTarget = apiTarget;
        this.searchRoot = searchRoot;
        this.searchItems = searchItems;
    }

    public String apiTarget() {
        return apiTarget;
    }

    public String searchRoot() {
        return searchRoot;
    }

    public String searchItems() {
        return searchItems;
    }
}
