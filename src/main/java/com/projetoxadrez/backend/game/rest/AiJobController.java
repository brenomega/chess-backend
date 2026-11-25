package com.projetoxadrez.backend.game.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.projetoxadrez.backend.game.application.AiJobService;
import java.util.UUID;
import java.util.regex.Pattern;
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

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private final AiJobService service;

    public AiJobController(AiJobService service) {
        this.service = service;
    }

    @PostMapping("/next")
    public ResponseEntity<AiJobResponse> next(@RequestBody AiJobNextRequest request) {
        return service.next(requireText(request.workerId()))
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
                requireText(request.workerId()), requireUuid(request.jobId()), requireUuid(request.gameId()),
                revision.longValue(), requireText(request.uci()));
        return new AiJobResultResponse("APPLIED", outcome.gameId(), outcome.revision());
    }

    private static UUID requireUuid(JsonNode node) {
        String value = requireText(node);
        if (!UUID_TEXT.matcher(value).matches()) {
            throw new AiJobService.InvalidResultException();
        }
        return UUID.fromString(value);
    }

    private static String requireText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw new AiJobService.InvalidResultException();
        }
        return node.textValue();
    }
}
