package com.chatflow.aisummary.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Proves the ai-summary-service GlobalExceptionHandler (extending
 * BaseExceptionHandler) is wired and correctly maps framework 4xx
 * conditions. Uses standaloneSetup with a dummy controller.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    @RequestMapping("/test")
    static class DummyController {

        @GetMapping("/with-param")
        public String withParam(@RequestParam("q") String q) {
            return "ok:" + q;
        }

        @GetMapping("/typed/{id}")
        public String typed(@PathVariable Long id) {
            return "ok:" + id;
        }

        @GetMapping("/with-header")
        public String withHeader(@RequestHeader("X-Required") String required) {
            return "ok:" + required;
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DummyController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void missingRequiredParam_returns400() throws Exception {
        mockMvc.perform(get("/test/with-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_REQUEST_PARAMETER"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void typeMismatch_returns400() throws Exception {
        mockMvc.perform(get("/test/typed/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void missingRequiredHeader_returns400() throws Exception {
        mockMvc.perform(get("/test/with-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_REQUEST_PARAMETER"))
                .andExpect(jsonPath("$.status").value(400));
    }
}
