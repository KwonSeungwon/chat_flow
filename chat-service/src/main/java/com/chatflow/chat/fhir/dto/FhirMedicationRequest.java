package com.chatflow.chat.fhir.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FhirMedicationRequest {
    private String id;
    private String patientId;
    private String medication;
    private String dosage;
    private String status;
    private String authoredOn;
}
