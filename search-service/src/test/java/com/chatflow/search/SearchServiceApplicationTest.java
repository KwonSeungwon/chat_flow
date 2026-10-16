package com.chatflow.search;

import com.chatflow.search.config.SearchKafkaConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    "spring.main.allow-bean-definition-overriding=true"
})
@ActiveProfiles("test")
class SearchServiceApplicationTest {

    @MockBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @Autowired
    @Qualifier(SearchKafkaConfig.BATCH_LISTENER_CONTAINER_FACTORY)
    private ConcurrentKafkaListenerContainerFactory<?, ?> batchFactory;

    @Autowired
    private CommonErrorHandler kafkaErrorHandler;

    @Test
    void contextLoads() {
        // Verifies Spring application context loads successfully
    }

    @Test
    void indexingFactory_isBatch_andUsesSharedDltErrorHandler() {
        assertThat(batchFactory.isBatchListener()).isTrue();
        assertThat(batchFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.BATCH);
        assertThat(ReflectionTestUtils.getField(batchFactory, "commonErrorHandler"))
                .isSameAs(kafkaErrorHandler);
    }
}
