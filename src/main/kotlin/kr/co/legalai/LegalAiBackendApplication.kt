package kr.co.legalai

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LegalAiBackendApplication

fun main(args: Array<String>) {
	runApplication<LegalAiBackendApplication>(*args)
}
