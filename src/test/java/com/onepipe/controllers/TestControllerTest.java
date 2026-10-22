package com.onepipe.controllers;

import com.onepipe.exceptions.customExceptions.InvalidPaymentException;
import com.onepipe.integration.OnepipeClient;
import com.onepipe.integration.dto.OnePipeResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestControllerTest {
    private final OnepipeClient client = mock(OnepipeClient.class);
    private final TestController controller = new TestController(client);

    @Test
    void diagnosticOnlyQueriesTheSuppliedReferenceWithoutCreatingAnInvoice() {
        var expected = OnePipeResponse.builder().status("Pending").transactionRef("synthetic-reference").build();
        when(client.queryTransaction("synthetic-reference")).thenReturn(expected);
        assertThat(controller.testOnePipeCall("synthetic-reference").getBody()).isSameAs(expected);
        verify(client).queryTransaction("synthetic-reference"); verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @NullAndEmptySource @ValueSource(strings = {" ", "\t"})
    void invalidDiagnosticReferencesNeverReachTheAdapter(String reference) {
        assertThatThrownBy(() -> controller.testOnePipeCall(reference)).isInstanceOf(InvalidPaymentException.class);
        verifyNoInteractions(client);
    }
}
