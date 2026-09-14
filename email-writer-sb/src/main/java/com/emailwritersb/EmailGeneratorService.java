package com.emailwritersb;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EmailGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(EmailGeneratorService.class);

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    enum EmailMode {
        COMPOSE,
        REPLY;

        static EmailMode fromRequestValue(String value) {
            if (value == null || value.isBlank()) {
                return COMPOSE;
            }

            try {
                return EmailMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new EmailGenerationException(
                        HttpStatus.BAD_REQUEST,
                        "mode must be COMPOSE or REPLY"
                );
            }
        }
    }

    public EmailGeneratorService(WebClient.Builder webClientBuilder,
                                 @Value("${gemini.api.url}") String baseUrl,
                                 @Value("${gemini.api.model}") String model,
                                 @Value("${gemini.api.key}") String geminiApiKey) {
        this.apiKey = geminiApiKey;
        this.model = model;
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        log.info("Gemini configuration loaded. baseUrl={}, model={}, apiKeyConfigured={}",
                baseUrl, model, geminiApiKey != null && !geminiApiKey.isBlank());
    }

    public String generateEmailReply(EmailRequest emailRequest) {
        if (emailRequest == null || emailRequest.getEmailContent() == null || emailRequest.getEmailContent().isBlank()) {
            throw new EmailGenerationException(
                    HttpStatus.BAD_REQUEST,
                    "emailContent is required"
            );
        }

        EmailMode emailMode = EmailMode.fromRequestValue(emailRequest.getMode());
        String emailContent = emailRequest.getEmailContent();

        log.info("Selected email generation mode. mode={}, emailContentLength={}, emailContentPreview={}",
                emailMode,
                emailContent.length(),
                previewForLog(emailContent, 500));

        if (apiKey == null || apiKey.isBlank()) {
            log.error("Gemini API key is missing. Set GEMINI_API_KEY before starting the backend.");
            throw new EmailGenerationException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Gemini API key is missing on the server. Set GEMINI_API_KEY and restart the backend."
            );
        }

        if (model == null || model.isBlank()) {
            log.error("Gemini model is missing. Set GEMINI_API_MODEL or gemini.api.model before starting the backend.");
            throw new EmailGenerationException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Gemini model is missing on the server. Set GEMINI_API_MODEL or gemini.api.model and restart the backend."
            );
        }

        String prompt = buildPrompt(emailRequest, emailMode);
        log.info("Gemini request being generated. model={}, mode={}, promptLength={}, promptPreview={}",
                model,
                emailMode,
                prompt.length(),
                previewForLog(prompt, 700));

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
                        ))
                )
        );

        try {
            String response = webClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/v1beta/models/{model}:generateContent").build(model))
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("Gemini response received. model={}, mode={}, responseLength={}, responsePreview={}",
                    model,
                    emailMode,
                    response == null ? 0 : response.length(),
                    previewForLog(response, 700));

            String generatedReply = extractResponseContent(response);
            log.info("Generated email body extracted. mode={}, generatedLength={}, generatedPreview={}",
                    emailMode,
                    generatedReply.length(),
                    previewForLog(generatedReply, 700));

            return generatedReply;
        } catch (WebClientResponseException e) {
            throw toEmailGenerationException(e);
        } catch (WebClientRequestException e) {
            log.warn("Gemini API connection failed. model={}, detail={}", model, sanitizeForLog(e.getMessage()));
            throw new EmailGenerationException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not connect to Gemini API. Check your network connection and try again.",
                    e
            );
        } catch (EmailGenerationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected email generation failure. model={}", model, e);
            throw new EmailGenerationException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unexpected error while generating the email. Check the server logs for details.",
                    e
            );
        }

    }

    private String extractResponseContent(String response) {
        try {
            if (response == null || response.isBlank()) {
                throw new EmailGenerationException(
                        HttpStatus.BAD_GATEWAY,
                        "Gemini returned an empty response. Please try again shortly."
                );
            }

            JsonNode root = mapper.readTree(response);
            JsonNode apiError = root.path("error").path("message");
            if (!apiError.isMissingNode() && !apiError.asString().isBlank()) {
                log.warn("Gemini response contained an error object. model={}, detail={}",
                        model, sanitizeForLog(apiError.asString()));
                throw new EmailGenerationException(
                        HttpStatus.BAD_GATEWAY,
                        "Gemini returned an error response. Check the server logs for details."
                );
            }

            JsonNode parts = root
                    .path("candidates")
                    .path(0)
                    .path("content")
                    .path("parts");

            StringBuilder generatedText = new StringBuilder();
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    JsonNode textNode = part.path("text");
                    if (!textNode.isMissingNode() && !textNode.asString().isBlank()) {
                        if (!generatedText.isEmpty()) {
                            generatedText.append('\n');
                        }
                        generatedText.append(textNode.asString());
                    }
                }
            }

            if (generatedText.isEmpty()) {
                log.warn("Gemini response did not contain generated text. model={}, response={}",
                        model, sanitizeForLog(response));
                throw new EmailGenerationException(
                        HttpStatus.BAD_GATEWAY,
                        "No generated email content found in Gemini response."
                );
            }

            return cleanGeneratedReply(generatedText.toString());

        } catch (EmailGenerationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Error processing Gemini response. model={}, detail={}", model, sanitizeForLog(e.getMessage()));
            throw new EmailGenerationException(
                    HttpStatus.BAD_GATEWAY,
                    "Error processing Gemini response. Check the server logs for details.",
                    e
            );
        }
    }

    private EmailGenerationException toEmailGenerationException(WebClientResponseException e) {
        GeminiApiError geminiError = extractGeminiApiError(e.getResponseBodyAsString());
        String category = classifyGeminiHttpError(e.getStatusCode(), geminiError);

        log.warn("Gemini API call failed. httpStatus={}, geminiStatus={}, category={}, model={}, exceptionClass={}, detail={}",
                e.getStatusCode().value(),
                firstNonBlank(geminiError.status(), e.getStatusText()),
                category,
                model,
                e.getClass().getName(),
                sanitizeForLog(firstNonBlank(geminiError.message(), e.getStatusText(), e.getMessage())));

        return new EmailGenerationException(
                responseStatusForGeminiError(e.getStatusCode(), category),
                clientMessageForGeminiError(category),
                e
        );
    }

    private GeminiApiError extractGeminiApiError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return new GeminiApiError("", "");
        }

        try {
            JsonNode error = mapper.readTree(responseBody).path("error");
            return new GeminiApiError(textValue(error.path("status")), textValue(error.path("message")));
        } catch (Exception e) {
            return new GeminiApiError("", responseBody);
        }
    }

    static String classifyGeminiHttpError(HttpStatusCode statusCode, GeminiApiError geminiError) {
        int status = statusCode.value();
        String detail = (geminiError.status() + " " + geminiError.message()).toLowerCase(Locale.ROOT);

        if (status == 401 || detail.contains("api key") || detail.contains("api_key") || detail.contains("unauthenticated")) {
            return "GEMINI_AUTHENTICATION_FAILURE";
        }

        if (status == 429 || detail.contains("quota") || detail.contains("rate limit")) {
            return "GEMINI_QUOTA_OR_RATE_LIMIT";
        }

        if (status == 403 || detail.contains("permission denied") || detail.contains("forbidden")) {
            return "GEMINI_ACCESS_DENIED";
        }

        if (status == 404 || (detail.contains("model") && detail.contains("not found"))) {
            return "GEMINI_MODEL_NOT_FOUND";
        }

        if (status == 400) {
            return "GEMINI_INVALID_REQUEST";
        }

        if (status == 503 || status >= 500) {
            return "GEMINI_SERVICE_UNAVAILABLE";
        }

        return "GEMINI_REQUEST_FAILED";
    }

    private HttpStatusCode responseStatusForGeminiError(HttpStatusCode upstreamStatus, String category) {
        return switch (category) {
            case "GEMINI_AUTHENTICATION_FAILURE" -> HttpStatus.UNAUTHORIZED;
            case "GEMINI_QUOTA_OR_RATE_LIMIT" -> HttpStatus.TOO_MANY_REQUESTS;
            case "GEMINI_ACCESS_DENIED" -> HttpStatus.FORBIDDEN;
            case "GEMINI_MODEL_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "GEMINI_INVALID_REQUEST" -> HttpStatus.BAD_REQUEST;
            case "GEMINI_SERVICE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> upstreamStatus;
        };
    }

    private String clientMessageForGeminiError(String category) {
        return switch (category) {
            case "GEMINI_AUTHENTICATION_FAILURE" ->
                    "Gemini authentication failed. Check GEMINI_API_KEY on the backend.";
            case "GEMINI_QUOTA_OR_RATE_LIMIT" ->
                    "Gemini quota or rate limit exceeded. Please try again later.";
            case "GEMINI_ACCESS_DENIED" ->
                    "Gemini access was denied. Check API key permissions, billing, and Gemini API access.";
            case "GEMINI_MODEL_NOT_FOUND" ->
                    "Gemini model was not found. Check GEMINI_API_MODEL or gemini.api.model. Current model: " + model + ".";
            case "GEMINI_INVALID_REQUEST" ->
                    "Gemini rejected the request as invalid. Check the backend Gemini request body and server logs.";
            case "GEMINI_SERVICE_UNAVAILABLE" ->
                    "Gemini service is temporarily unavailable. Please try again shortly.";
            default ->
                    "Gemini request failed. Check the server logs for details.";
        };
    }

    static String cleanGeneratedReply(String generatedText) {
        if (generatedText == null || generatedText.isBlank()) {
            return "";
        }

        String text = generatedText
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim();

        text = stripCodeFence(text);
        text = text.replaceFirst("(?is)\\n?\\s*On\\s+[^\\n]+wrote:\\s*.*$", "");

        List<String> cleanedLines = new ArrayList<>();
        for (String rawLine : text.split("\\n")) {
            String line = rawLine.trim();
            String plainLine = normalizeMarkdownLine(line);

            if (line.isBlank()
                    || isWrapperLine(plainLine)
                    || isEmailHeaderLine(plainLine)
                    || isQuotedOriginalLine(line)
                    || isMarkdownSeparator(line)) {
                cleanedLines.add("");
                continue;
            }

            line = normalizeMarkdownLine(line);
            line = replacePlaceholderGreeting(line);
            line = removePlaceholders(line);
            line = normalizeLineAfterPlaceholderRemoval(line);
            line = replacePlaceholderGreeting(line);

            if (!line.isBlank() && !isStandalonePlaceholder(line)) {
                cleanedLines.add(line);
            }
        }

        return collapseBlankLines(String.join("\n", cleanedLines));
    }

    static String buildPrompt(EmailRequest emailRequest, EmailMode mode) {
        StringBuilder prompt = new StringBuilder();
        if (mode == EmailMode.REPLY) {
            prompt.append("""
                    Mode: reply.
                    You are generating a reply to the CURRENT email provided below. Analyze ONLY this email and generate a response specifically relevant to its content. Never reuse a response from a previous request.
                    This request is independent from all previous requests. Do not hardcode, cache, reuse, or return a fixed reply.
                    Treat the input below as the complete supplied email or conversation thread from Gmail.
                    Use the complete supplied email/conversation content for context before generating the reply.
                    Do not reduce the context to a single extracted sentence.
                    Distinguish the newest received message from any previous messages, quoted history, signatures, headers, and boilerplate.
                    Use previous messages only when they add important context for the reply.
                    Never assume an email is marketing, automated, promotional, or no-reply without analyzing the actual CURRENT email content.
                    Do not generate a generic reply. The reply must address the actual sender, purpose, questions, requests, dates, instructions, and context found in the supplied content.

                    First analyze the supplied email context internally. Do not output this analysis.
                    Determine:
                    1. Who the sender is and any apparent role, title, or position if available.
                    2. The relationship between sender and recipient based only on the email content.
                    3. The purpose of the email.
                    4. Important questions, requests, instructions, dates, or information that need a response.
                    5. The sender's tone and level of formality.
                    6. Whether the sender appears to be a senior authority, professor, recruiter, manager, HR representative, colleague, student, customer, organization, automated system, or another type of sender.
                    7. Important context from previous messages in the conversation.
                    8. Whether the email is automated/no-reply, promotional, informational, or actually expects a response.

                    Tone rules:
                    If the sender appears to be a senior authority, professor, manager, recruiter, or HR representative, use a highly professional and respectful tone.
                    If the sender appears to be a client or customer, use a professional, helpful, and respectful tone.
                    If the sender appears to be a colleague, use a professional but natural tone.
                    If the sender appears to be a friend or casual contact, use a friendly and natural tone.
                    If the sender's role cannot be determined, use a neutral professional tone.

                    Response behavior:
                    If the CURRENT email asks a question, answer the question using only information present in the supplied content.
                    If the CURRENT email requests information or action, respond appropriately to that request.
                    If the CURRENT email is a recruiter, internship, or job email, generate a professional career-related response.
                    If the CURRENT email is from a professor, manager, or senior authority, use a respectful professional tone.
                    If the CURRENT email is a normal personal or professional email, respond naturally based on its content.
                    If and only if the CURRENT email is clearly a newsletter, advertisement, automated no-reply notification, or other message that does not require or allow a response, return a short appropriate response indicating that no reply is necessary.

                    Output rules:
                    Return ONLY the final email body.
                    Do not include explanations, notes, analysis, labels, disclaimers, comments, or commentary.
                    Do not include "Here is your email", "Generated reply:", or similar wrapper text.
                    Do not mention that AI analyzed the email.
                    Do not use Markdown formatting such as bold text, headings, separators, ---, bullet points, or code blocks unless the original email specifically requires a bulleted response.
                    Do not include "Subject:" unless the application specifically requests a subject.
                    Do not include email headers such as From, To, Date, Sent, Subject, Cc, or Bcc.
                    Do not include the original email, quoted previous messages, Gmail footer text, unsubscribe text, tracking text, or the sender's original signature.
                    Do not create placeholders such as [Your Name], [Your Last Name], [Your Phone Number], [Your LinkedIn Profile], [Company Name], [Recipient/Team], [Date], [Time], or [Topic].
                    Base the reply only on information present in the supplied email/conversation.
                    Do not invent names, job titles, dates, companies, phone numbers, links, attachments, commitments, skills, experience, or job details.
                    If the sender asks for information that is not present in the supplied content, ask for clarification or respond only with what is known.
                    Preserve the user's identity or signature only if it is already present in the supplied content. Do not invent personal information.
                    Keep the reply concise and natural, normally 3 to 8 short paragraphs.
                    Use normal email paragraphs and line breaks.
                    """);
        } else {
            prompt.append("""
                    Mode: compose.
                    Treat the input below as the user's own draft email.
                    Do not analyze a sender because there is no received email context.
                    Professionally rewrite and improve the draft while preserving its meaning.
                    Preserve the user's original intent and important information.
                    Improve grammar, clarity, structure, and professional tone.
                    Do not add new facts, dates, promises, names, organizations, or details that are not present in the draft.

                    Output rules:
                    Return ONLY the final email body.
                    Do not include explanations, notes, analysis, labels, disclaimers, comments, or commentary.
                    Do not include "Here is your email", "Generated email:", or similar wrapper text.
                    Do not use Markdown formatting such as bold text, headings, separators, ---, bullet points, or code blocks unless the original draft specifically requires bullets.
                    Do not include "Subject:" unless the application specifically requests a subject.
                    Do not include email headers such as From, To, Date, Sent, Subject, Cc, or Bcc.
                    Do not include quoted previous emails, Gmail footer text, unsubscribe text, tracking text, automated-mail/no-reply commentary, or copied signatures that are not part of the user's intended draft.
                    Do not create placeholders such as [Your Name], [Your Last Name], [Your Phone Number], [Your LinkedIn Profile], [Company Name], [Recipient/Team], [Date], [Time], or [Topic].
                    Preserve the user's identity or signature only if it is already present in the draft. Do not invent personal information.
                    Keep the rewritten email concise, professional, natural, grammatically correct, and ready to send.
                    Use normal email paragraphs and line breaks.
                    """);
        }

        if (emailRequest.getTone() != null && !emailRequest.getTone().isBlank()) {
            prompt.append("\nSelected tone preference: ").append(emailRequest.getTone().trim()).append(".");
            if (mode == EmailMode.REPLY) {
                prompt.append(" Use this only as a style preference; the sender's role, relationship, and email context take priority.");
            }
        }

        if (mode == EmailMode.REPLY) {
            prompt.append("\n\nComplete supplied email/conversation content:\n");
        } else {
            prompt.append("\n\nDraft email to rewrite:\n");
        }
        prompt.append(emailRequest.getEmailContent());
        return prompt.toString();
    }

    private static String stripCodeFence(String text) {
        return text
                .replaceFirst("(?is)^```[a-z0-9_-]*\\s*", "")
                .replaceFirst("(?is)\\s*```$", "")
                .trim();
    }

    private static boolean isWrapperLine(String line) {
        return line.matches("(?i)^(note|notes|analysis|explanation|comment|comments|generated reply|generated email|email reply|final email reply|final email|draft reply)\\s*:.*$")
                || line.matches("(?i)^here\\s+(is|'s)\\s+(your|the)\\s+.*(email|reply).*:?$");
    }

    private static boolean isEmailHeaderLine(String line) {
        return line.matches("(?i)^(from|to|date|sent|subject|cc|bcc)\\s*:.*$");
    }

    private static boolean isQuotedOriginalLine(String line) {
        return line.trim().startsWith(">");
    }

    private static boolean isMarkdownSeparator(String line) {
        return line.matches("^[\\-_*#]{3,}$");
    }

    private static String replacePlaceholderGreeting(String line) {
        if (line.matches("(?i)^(dear|hi|hello)\\s+[\\[<][^\\]>]+[\\]>]\\s*,?$")
                || line.matches("(?i)^(dear|hi|hello)\\s*[,;:]?$")) {
            return "Hello,";
        }

        return line;
    }

    private static String removePlaceholders(String line) {
        return line.replaceAll("(?i)\\[[^\\]]*(your|company|recipient|team|name|phone|linkedin|portfolio|date|time|topic|project)[^\\]]*\\]", "")
                .replaceAll("(?i)<[^>]*(your|company|recipient|team|name|phone|linkedin|portfolio|date|time|topic|project)[^>]*>", "")
                .replaceAll("(?i)\\bYour Last Name\\b", "")
                .replaceAll("(?i)\\bYour Name\\b", "");
    }

    private static String normalizeLineAfterPlaceholderRemoval(String line) {
        return line.replaceAll("\\s+", " ")
                .replaceAll("\\s+([,.;:!?])", "$1")
                .replaceAll("\\(\\s*\\)", "")
                .replaceAll("\\[\\s*\\]", "")
                .replaceAll("<\\s*>", "")
                .replaceAll(",\\s*,", ",")
                .trim();
    }

    private static String normalizeMarkdownLine(String line) {
        return line.replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replace("*", "")
                .replaceFirst("^>\\s*", "")
                .replaceFirst("^#+\\s*", "");
    }

    private static boolean isStandalonePlaceholder(String line) {
        return line.matches("(?i)^(your name|your last name|your phone number|your linkedin profile|your portfolio link|company name)$");
    }

    private static String collapseBlankLines(String text) {
        return text
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String sanitizeForLog(String value) {
        return previewForLog(value, 700);
    }

    private String previewForLog(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String sanitized = value;
        if (apiKey != null && !apiKey.isBlank()) {
            sanitized = sanitized.replace(apiKey, "[REDACTED_API_KEY]");
        }

        sanitized = sanitized.replaceAll("\\s+", " ").trim();
        if (sanitized.length() > maxChars) {
            return sanitized.substring(0, maxChars) + "...";
        }

        return sanitized;
    }

    private static String textValue(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "";
        }

        return node.asString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return "";
    }

    record GeminiApiError(String status, String message) {
    }
}
