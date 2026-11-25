package com.projetoxadrez.backend.game.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.projetoxadrez.backend.game.application.AiJobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ai/jobs")
public class AiJobController {

    private final AiJobService service;

    public AiJobController(AiJobService service) {
        this.service = service;
    }

    @PostMapping("/next")
    public ResponseEntity<AiJobResponse> next(@RequestBody AiJobNextRequest request) {
        return service.next(requireTextWorkerId(request.workerId()))
                .map(job -> ResponseEntity.ok(AiJobResponse.from(job)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/result")
    @ResponseStatus(HttpStatus.OK)
    public AiJobResultResponse result(@RequestBody AiJobResultRequest request) {
        JsonNode revision = request.revision();
        if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong()) {
            throw new AiJobService.InvalidResultException();
        }
        AiJobService.AiJobOutcome outcome = service.result(
                requireTextWorkerId(request.workerId()), request.jobId(), request.gameId(), revision.longValue(),
                request.uci());
        return new AiJobResultResponse("APPLIED", outcome.gameId(), outcome.revision());
    }

    private static String requireTextWorkerId(JsonNode workerId) {
        if (workerId == null || !workerId.isTextual()) {
            throw new AiJobService.InvalidResultException();
        }
        return workerId.textValue();
    }
}
