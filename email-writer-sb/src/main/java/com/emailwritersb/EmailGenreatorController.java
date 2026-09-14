package com.emailwritersb;

import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/email")
@AllArgsConstructor
@CrossOrigin(origins = "*")
public class EmailGenreatorController {

    private static final Logger log = LoggerFactory.getLogger(EmailGenreatorController.class);

    private final EmailGeneratorService emailGeneratorService;

    @PostMapping({"/generate", "/genreate"})
    public ResponseEntity<String> generateEmail(@RequestBody EmailRequest emailRequest) {
        if (emailRequest == null || emailRequest.getEmailContent() == null || emailRequest.getEmailContent().isBlank()) {
            log.warn("Rejected email generation request because emailContent was missing or blank.");
            return ResponseEntity.badRequest().body("emailContent is required");
        }

        String emailContent = emailRequest.getEmailContent();
        log.info("Received email generation request. mode={}, emailContentLength={}, emailContentPreview={}",
                valueForLog(emailRequest.getMode()),
                emailContent.length(),
                previewForLog(emailContent, 500));

        try {
            String generatedReply = emailGeneratorService.generateEmailReply(emailRequest);
            return ResponseEntity.ok(generatedReply);
        } catch (EmailGenerationException e) {
            log.warn("Email generation failed. status={}, message={}", e.getStatusCode().value(), e.getClientMessage());
            return ResponseEntity.status(e.getStatusCode()).body(e.getClientMessage());
        }
    }

    private static String previewForLog(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }

        return normalized.substring(0, maxChars) + "...";
    }

    private static String valueForLog(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value.trim();
    }

}
