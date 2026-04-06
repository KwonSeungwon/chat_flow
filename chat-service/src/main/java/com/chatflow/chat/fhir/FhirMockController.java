package com.chatflow.chat.fhir;

import com.chatflow.chat.fhir.dto.FhirMedicationRequest;
import com.chatflow.chat.fhir.dto.FhirPatient;
import com.chatflow.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/fhir")
public class FhirMockController {

    private static final List<FhirPatient> PATIENTS = List.of(
            FhirPatient.builder().id("P001").name("김민준").birthDate("1965-03-12").gender("male").roomNumber("301A").build(),
            FhirPatient.builder().id("P002").name("이서연").birthDate("1978-07-25").gender("female").roomNumber("302B").build(),
            FhirPatient.builder().id("P003").name("박지훈").birthDate("1952-11-08").gender("male").roomNumber("303A").build(),
            FhirPatient.builder().id("P004").name("최수아").birthDate("1990-01-30").gender("female").roomNumber("304C").build(),
            FhirPatient.builder().id("P005").name("정도윤").birthDate("1943-06-17").gender("male").roomNumber("305B").build(),
            FhirPatient.builder().id("P006").name("강하은").birthDate("1988-09-04").gender("female").roomNumber("306A").build(),
            FhirPatient.builder().id("P007").name("조현우").birthDate("1970-12-22").gender("male").roomNumber("307D").build(),
            FhirPatient.builder().id("P008").name("윤지아").birthDate("1935-04-15").gender("female").roomNumber("308B").build(),
            FhirPatient.builder().id("P009").name("장서준").birthDate("1961-08-03").gender("male").roomNumber("309A").build(),
            FhirPatient.builder().id("P010").name("임나은").birthDate("1995-02-28").gender("female").roomNumber("310C").build()
    );

    private static final List<FhirMedicationRequest> MEDICATIONS = List.of(
            FhirMedicationRequest.builder().id("MR001").patientId("P001").medication("아스피린 100mg").dosage("1정 1일 1회").status("active").authoredOn("2026-04-01").build(),
            FhirMedicationRequest.builder().id("MR002").patientId("P001").medication("메트포르민 500mg").dosage("1정 1일 2회 식후").status("active").authoredOn("2026-04-01").build(),
            FhirMedicationRequest.builder().id("MR003").patientId("P002").medication("암로디핀 5mg").dosage("1정 1일 1회").status("active").authoredOn("2026-04-02").build(),
            FhirMedicationRequest.builder().id("MR004").patientId("P003").medication("와파린 5mg").dosage("1정 1일 1회 저녁").status("active").authoredOn("2026-03-28").build(),
            FhirMedicationRequest.builder().id("MR005").patientId("P003").medication("푸로세미드 40mg").dosage("1정 1일 1회 아침").status("active").authoredOn("2026-03-28").build(),
            FhirMedicationRequest.builder().id("MR006").patientId("P004").medication("타이레놀 500mg").dosage("1정 필요시 (최대 4회/일)").status("active").authoredOn("2026-04-03").build(),
            FhirMedicationRequest.builder().id("MR007").patientId("P005").medication("리시노프릴 10mg").dosage("1정 1일 1회").status("active").authoredOn("2026-03-25").build(),
            FhirMedicationRequest.builder().id("MR008").patientId("P006").medication("세티리진 10mg").dosage("1정 1일 1회 취침 전").status("completed").authoredOn("2026-03-20").build(),
            FhirMedicationRequest.builder().id("MR009").patientId("P007").medication("오메프라졸 20mg").dosage("1정 1일 1회 식전").status("active").authoredOn("2026-04-01").build(),
            FhirMedicationRequest.builder().id("MR010").patientId("P008").medication("글리메피리드 2mg").dosage("1정 1일 1회 아침 식전").status("active").authoredOn("2026-03-30").build()
    );

    @GetMapping("/Patient")
    public ResponseEntity<ApiResponse<List<FhirPatient>>> searchPatients(
            @RequestParam(name = "name", required = false) String query) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.ok(ApiResponse.ok(PATIENTS));
        }
        List<FhirPatient> filtered = PATIENTS.stream()
                .filter(p -> p.getName().contains(query))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(filtered));
    }

    @GetMapping("/Patient/{id}")
    public ResponseEntity<ApiResponse<FhirPatient>> getPatient(@PathVariable String id) {
        return PATIENTS.stream()
                .filter(p -> p.getId().equals(id))
                .findFirst()
                .map(p -> ResponseEntity.ok(ApiResponse.ok(p)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("환자를 찾을 수 없습니다: " + id)));
    }

    @GetMapping("/MedicationRequest")
    public ResponseEntity<ApiResponse<List<FhirMedicationRequest>>> getMedicationRequests(
            @RequestParam(name = "patient") String patientId) {
        List<FhirMedicationRequest> meds = MEDICATIONS.stream()
                .filter(m -> m.getPatientId().equals(patientId))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(meds));
    }
}
