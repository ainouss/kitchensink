package io.github.ainouss.kitchensink.facade;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

import io.github.ainouss.kitchensink.facade.control.Control;
import io.github.ainouss.kitchensink.facade.control.ControlStore;

/** The facade's context: with no control document yet, the legacy owns reads and writes. */
@SpringBootTest
@Import(TestMongo.class)
class FacadeApplicationTests {

    @Autowired
    ControlStore control;
    @Autowired
    MongoTemplate mongo;

    @Test
    void without_a_control_document_the_legacy_owns_everything() {
        mongo.getCollection(ControlStore.COLLECTION).deleteMany(new org.bson.Document());
        control.refresh();

        assertThat(control.current().reads()).isEqualTo(Control.Reads.LEGACY);
        assertThat(control.current().writes()).isEqualTo(Control.Writes.LEGACY);
    }
}
