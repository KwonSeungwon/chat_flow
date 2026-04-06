package com.chatflow.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientCardPayload {
    private String patientName;
    private String roomNumber;
    private String diagnosis;
    private String allergies;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VitalSigns {
        private String bloodPressure;
        private Integer heartRate;
        private Integer respiratoryRate;
        private Double bodyTemperature;
        private Integer spO2;
    }

    private VitalSigns vitalSigns;
    private String notes;
}
