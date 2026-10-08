package dev.secdocs.answer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnswerController {

    private final AnswerService service;

    public AnswerController(AnswerService service) {
        this.service = service;
    }

    @PostMapping("/api/ask")
    public AnswerService.AnswerResponse ask(@Valid @RequestBody AskRequest request) throws Exception {
        return service.answer(request.question().trim());
    }

    public record AskRequest(@NotBlank @Size(max = 500) String question) {
    }
}
