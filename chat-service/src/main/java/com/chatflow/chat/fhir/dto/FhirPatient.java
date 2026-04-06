package com.chatflow.chat.fhir.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FhirPatient {
    private String id;
    private String name;
    private String birthDate;
    private String gender;
    private String roomNumber;
}
