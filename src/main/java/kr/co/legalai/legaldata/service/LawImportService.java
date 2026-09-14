package kr.co.legalai.legaldata.service;

public interface LawImportService {
    void collect();
    void collectVehicle();
    void collectAssault();
    void collectLabor();
    void embed();
}
