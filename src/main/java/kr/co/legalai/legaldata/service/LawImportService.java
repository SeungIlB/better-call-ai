package kr.co.legalai.legaldata.service;

public interface LawImportService {
    void collect();
    void collectVehicle();
    void collectAssault();
    void collectLabor();
    void collectConsumer();
    void collectFamily();
    void collectInheritance();
    void collectDefamation();
    void collectPersonalInjury();
    void embed();
}
