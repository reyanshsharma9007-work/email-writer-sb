package com.emailwritersb;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailGenreatorControllerTest {

    @Test
    void generateEmailPassesRequestAndEmailContentUnchangedToService() {
        EmailGeneratorService emailGeneratorService = mock(EmailGeneratorService.class);
        EmailGenreatorController controller = new EmailGenreatorController(emailGeneratorService);

        String emailContent = "  From: professor@example.edu\nPlease confirm your project topic by Friday.\n  ";
        EmailRequest request = new EmailRequest();
        request.setMode("reply");
        request.setEmailContent(emailContent);

        when(emailGeneratorService.generateEmailReply(request)).thenReturn("I will confirm the project topic by Friday.");

        ResponseEntity<String> response = controller.generateEmail(request);

        ArgumentCaptor<EmailRequest> requestCaptor = ArgumentCaptor.forClass(EmailRequest.class);
        verify(emailGeneratorService).generateEmailReply(requestCaptor.capture());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("I will confirm the project topic by Friday.", response.getBody());
        assertSame(request, requestCaptor.getValue());
        assertEquals(emailContent, requestCaptor.getValue().getEmailContent());
        assertEquals("reply", requestCaptor.getValue().getMode());
    }
}
