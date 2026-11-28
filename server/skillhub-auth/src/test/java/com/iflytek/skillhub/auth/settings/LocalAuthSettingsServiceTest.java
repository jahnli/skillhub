package com.iflytek.skillhub.auth.settings;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.iflytek.skillhub.auth.exception.AuthFlowException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LocalAuthSettingsServiceTest {
    @Mock private SystemSettingRepository repository;

    @Test
    void separateServiceInstancesObserveCurrentStoredValue() {
        SystemSetting initial = new SystemSetting("auth.local",
                Map.of("passwordLoginEnabled", true));
        ReflectionTestUtils.setField(initial, "id", 1L);
        AtomicReference<SystemSetting> stored = new AtomicReference<>(initial);
        when(repository.findBySettingKey("auth.local")).thenAnswer(invocation -> Optional.of(stored.get()));
        LocalAuthSettingsService firstInstance = new LocalAuthSettingsService(repository);
        LocalAuthSettingsService secondInstance = new LocalAuthSettingsService(repository);

        assertThat(firstInstance.current().passwordLoginEnabled()).isTrue();
        SystemSetting updated = new SystemSetting("auth.local",
                Map.of("passwordLoginEnabled", false));
        ReflectionTestUtils.setField(updated, "id", 1L);
        stored.set(updated);

        assertThat(secondInstance.current().passwordLoginEnabled()).isFalse();
        assertThat(firstInstance.current().passwordLoginEnabled()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void legacyRegistrationFlagIsIgnoredWithoutChangingPasswordLogin(boolean login, boolean registration) {
        SystemSetting setting = new SystemSetting("auth.local",
                Map.of("passwordLoginEnabled", login, "selfRegistrationEnabled", registration));
        ReflectionTestUtils.setField(setting, "id", 1L);
        when(repository.findBySettingKey("auth.local")).thenReturn(Optional.of(setting));
        assertThat(new LocalAuthSettingsService(repository).current().passwordLoginEnabled()).isEqualTo(login);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void singlePasswordSettingControlsLogin(boolean enabled) {
        SystemSetting setting = new SystemSetting("auth.local", Map.of("passwordLoginEnabled", enabled));
        ReflectionTestUtils.setField(setting, "id", 1L);
        when(repository.findBySettingKey("auth.local")).thenReturn(Optional.of(setting));
        LocalAuthSettingsService service = new LocalAuthSettingsService(repository);
        if (enabled) {
            assertThatCode(service::requirePasswordLogin).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(service::requirePasswordLogin).isInstanceOf(AuthFlowException.class)
                    .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void malformedOrUnknownSettingsFailClosed() {
        for (Map<String, Object> value : java.util.List.<Map<String, Object>>of(
                Map.of(), Map.of("passwordLoginEnabled", "true"),
                Map.of("passwordLoginEnabled", true, "unknown", true),
                Map.of("passwordLoginEnabled", true, "selfRegistrationEnabled", "true"),
                Map.of("passwordLoginEnabled", true, "selfRegistrationEnabled", true, "unknown", true))) {
            when(repository.findBySettingKey("auth.local"))
                    .thenReturn(Optional.of(new SystemSetting("auth.local", value)));
            assertThatThrownBy(() -> new LocalAuthSettingsService(repository).current())
                    .isInstanceOf(AuthFlowException.class)
                    .extracting("status").isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Test
    void missingSettingFailsClosed() {
        when(repository.findBySettingKey("auth.local")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new LocalAuthSettingsService(repository).requirePasswordLogin())
                .isInstanceOf(AuthFlowException.class)
                .extracting("status").isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void databaseFailureFailsClosedWithServiceUnavailable() {
        when(repository.findBySettingKey("auth.local"))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> new LocalAuthSettingsService(repository).requirePasswordLogin())
                .isInstanceOf(AuthFlowException.class)
                .extracting("status").isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
