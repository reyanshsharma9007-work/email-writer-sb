package com.emailwritersb;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailGeneratorServiceTest {

    @Test
    void cleanGeneratedReplyRemovesWrappersMarkdownPlaceholdersAndQuotedHistory() {
        String rawReply = """
                ```text
                Note: The original email was sent from an automated address.
                ---
                **Subject:** Inquiry regarding Internship Opportunity
                Generated email:
                Analysis: This reply should be concise.
                > Original quoted line
                Dear Hiring Team,

                I hope you are doing well.

                I recently came across the internship opportunity through Unstop and I am interested in the opportunity.

                Best regards,
                **Reyansh [Your Last Name]**
                [Your Phone Number]

                On Fri, Sep 11, 2026 at 8:30 PM Team Unstop <noreply@unstop.news> wrote:
                Original email content
                ```
                """;

        String cleanedReply = EmailGeneratorService.cleanGeneratedReply(rawReply);

        assertEquals("""
                Dear Hiring Team,

                I hope you are doing well.

                I recently came across the internship opportunity through Unstop and I am interested in the opportunity.

                Best regards,
                Reyansh
                """.trim(), cleanedReply);
        assertFalse(cleanedReply.contains("Note:"));
        assertFalse(cleanedReply.contains("Generated email:"));
        assertFalse(cleanedReply.contains("Analysis:"));
        assertFalse(cleanedReply.contains(">"));
        assertFalse(cleanedReply.contains("Subject:"));
        assertFalse(cleanedReply.contains("On Fri"));
        assertFalse(cleanedReply.contains("[Your"));
        assertFalse(cleanedReply.contains("**"));
    }

    @Test
    void classifyGeminiHttpErrorDistinguishesCommonFailures() {
        assertEquals(
                "GEMINI_SERVICE_UNAVAILABLE",
                EmailGeneratorService.classifyGeminiHttpError(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        new EmailGeneratorService.GeminiApiError("", "Service unavailable")
                )
        );
        assertEquals(
                "GEMINI_AUTHENTICATION_FAILURE",
                EmailGeneratorService.classifyGeminiHttpError(
                        HttpStatus.BAD_REQUEST,
                        new EmailGeneratorService.GeminiApiError("INVALID_ARGUMENT", "API key not valid")
                )
        );
        assertEquals(
                "GEMINI_MODEL_NOT_FOUND",
                EmailGeneratorService.classifyGeminiHttpError(
                        HttpStatus.NOT_FOUND,
                        new EmailGeneratorService.GeminiApiError("NOT_FOUND", "Model not found")
                )
        );
        assertEquals(
                "GEMINI_QUOTA_OR_RATE_LIMIT",
                EmailGeneratorService.classifyGeminiHttpError(
                        HttpStatus.TOO_MANY_REQUESTS,
                        new EmailGeneratorService.GeminiApiError("RESOURCE_EXHAUSTED", "Quota exceeded")
                )
        );
    }

    @Test
    void emailModeDefaultsToComposeAndRejectsInvalidValues() {
        assertEquals(EmailGeneratorService.EmailMode.COMPOSE, EmailGeneratorService.EmailMode.fromRequestValue(null));
        assertEquals(EmailGeneratorService.EmailMode.COMPOSE, EmailGeneratorService.EmailMode.fromRequestValue(""));
        assertEquals(EmailGeneratorService.EmailMode.COMPOSE, EmailGeneratorService.EmailMode.fromRequestValue("compose"));
        assertEquals(EmailGeneratorService.EmailMode.REPLY, EmailGeneratorService.EmailMode.fromRequestValue(" reply "));

        EmailGenerationException exception = assertThrows(
                EmailGenerationException.class,
                () -> EmailGeneratorService.EmailMode.fromRequestValue("draft")
        );
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("mode must be COMPOSE or REPLY", exception.getClientMessage());
    }

    @Test
    void replyPromptPassesCompleteConversationContentAndRequiresInternalContextAnalysis() {
        String conversation = """
                From: Prof. Anita Rao <anita.rao@example.edu>
                To: Reyansh
                Subject: Revised abstract

                Dear Reyansh,

                Please send the revised abstract by September 18 and confirm whether you can attend Friday's review meeting.

                Regards,
                Prof. Anita Rao

                On Monday, Reyansh wrote:
                I can revise the abstract this week. Should I include the updated methodology section?
                """;

        EmailRequest request = new EmailRequest();
        request.setEmailContent(conversation);
        request.setTone("casual");

        String prompt = EmailGeneratorService.buildPrompt(request, EmailGeneratorService.EmailMode.REPLY);

        assertTrue(prompt.contains("Mode: reply."));
        assertTrue(prompt.contains("You are generating a reply to the CURRENT email provided below. Analyze ONLY this email and generate a response specifically relevant to its content. Never reuse a response from a previous request."));
        assertTrue(prompt.contains("This request is independent from all previous requests. Do not hardcode, cache, reuse, or return a fixed reply."));
        assertTrue(prompt.contains("Use the complete supplied email/conversation content for context before generating the reply."));
        assertTrue(prompt.contains("Do not reduce the context to a single extracted sentence."));
        assertTrue(prompt.contains("Never assume an email is marketing, automated, promotional, or no-reply without analyzing the actual CURRENT email content."));
        assertTrue(prompt.contains("First analyze the supplied email context internally. Do not output this analysis."));
        assertTrue(prompt.contains("sender's role, relationship, and email context take priority."));
        assertTrue(prompt.contains("If the CURRENT email asks a question, answer the question using only information present in the supplied content."));
        assertTrue(prompt.contains("If and only if the CURRENT email is clearly a newsletter, advertisement, automated no-reply notification, or other message that does not require or allow a response"));
        assertTrue(prompt.contains("Complete supplied email/conversation content:"));
        assertTrue(prompt.endsWith(conversation));
        assertTrue(prompt.contains("Please send the revised abstract by September 18"));
        assertTrue(prompt.contains("Should I include the updated methodology section?"));
        assertFalse(prompt.contains("Before writing the reply, ignore any email headers"));
        assertFalse(prompt.contains("quoted sections beginning with lines like"));
    }

    @Test
    void replyPromptUsesFreshCurrentEmailContentForEachRequestWithoutTrimming() {
        String firstEmail = "  From: recruiter@example.com\nCan you share your resume for the backend internship?\n  ";
        String secondEmail = "\nFrom: manager@example.com\nPlease confirm whether the deployment is complete by 5 PM.\n";

        EmailRequest firstRequest = new EmailRequest();
        firstRequest.setEmailContent(firstEmail);

        EmailRequest secondRequest = new EmailRequest();
        secondRequest.setEmailContent(secondEmail);

        String firstPrompt = EmailGeneratorService.buildPrompt(firstRequest, EmailGeneratorService.EmailMode.REPLY);
        String secondPrompt = EmailGeneratorService.buildPrompt(secondRequest, EmailGeneratorService.EmailMode.REPLY);

        assertTrue(firstPrompt.endsWith(firstEmail));
        assertTrue(secondPrompt.endsWith(secondEmail));
        assertTrue(firstPrompt.contains("backend internship"));
        assertTrue(secondPrompt.contains("deployment is complete by 5 PM"));
        assertFalse(firstPrompt.contains("deployment is complete by 5 PM"));
        assertFalse(secondPrompt.contains("backend internship"));
    }

    @Test
    void composePromptTreatsInputAsUserDraftWithoutSenderAnalysis() {
        String draft = """
                hello,
                i wanted to follow up about the meeting and confirm i will send the notes tomorrow.
                thanks,
                Reyansh
                """;

        EmailRequest request = new EmailRequest();
        request.setEmailContent(draft);

        String prompt = EmailGeneratorService.buildPrompt(request, EmailGeneratorService.EmailMode.COMPOSE);

        assertTrue(prompt.contains("Mode: compose."));
        assertTrue(prompt.contains("Treat the input below as the user's own draft email."));
        assertTrue(prompt.contains("Do not analyze a sender because there is no received email context."));
        assertTrue(prompt.contains("Draft email to rewrite:"));
        assertTrue(prompt.endsWith(draft));
        assertFalse(prompt.contains("Who the sender is and any apparent role"));
        assertFalse(prompt.contains("Complete supplied email/conversation content:"));
    }

    @Test
    void cleanGeneratedReplyRemovesSubjectsAndPlaceholderGreetings() {
        String rawReply = """
                Here is your professional email:
                Subject: Planning the Workshop

                Hi [Name/Team],

                We would like to organize the workshop and discuss the required arrangements.

                Best regards,
                [Your Name]
                """;

        String cleanedReply = EmailGeneratorService.cleanGeneratedReply(rawReply);

        assertEquals("""
                Hello,

                We would like to organize the workshop and discuss the required arrangements.

                Best regards,
                """.trim(), cleanedReply);
        assertFalse(cleanedReply.contains("Subject:"));
        assertFalse(cleanedReply.contains("["));
        assertFalse(cleanedReply.contains("Here is"));
    }
}
