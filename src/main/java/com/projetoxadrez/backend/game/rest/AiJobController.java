package com.projetoxadrez.backend.game.rest;

import com.projetoxadrez.backend.game.application.AiJobService;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrato com o worker de IA (EAP 2.14.2), descrito em {@code docs/contracts/backend-ai.md}.
 *
 * <p>Não é parte da API do cliente: quem fala aqui é o worker.
 */
@RestController
@RequestMapping("/v1/ai/jobs")
public class AiJobController {

    private final AiJobService service;

    public AiJobController(AiJobService service) {
        this.service = service;
    }

    @PostMapping("/next")
    public ResponseEntity<AiJobResponse> next(@RequestBody(required = false) AiJobNextRequest request) {
        return Optional.ofNullable(request)
                .flatMap(body -> service.next(body.workerId()))
                .map(job -> ResponseEntity.ok(AiJobResponse.from(job)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/result")
    @ResponseStatus(HttpStatus.OK)
    public AiJobResultResponse result(@RequestBody(required = false) AiJobResultRequest request) {
        if (request == null || request.revision() == null) {
            throw new AiJobService.InvalidResultException();
        }
        AiJobService.AiJobOutcome outcome = service.result(
                request.workerId(), request.jobId(), request.gameId(), request.revision(), request.uci());
        return new AiJobResultResponse("APPLIED", outcome.gameId(), outcome.revision());
    }
}
