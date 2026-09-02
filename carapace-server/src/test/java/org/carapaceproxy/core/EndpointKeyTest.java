package org.carapaceproxy.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EndpointKeyTest {

    @Test
    void endpointKeyTest() {
        {
            EndpointKey entryPoint = EndpointKey.make("localhost:8080");
            assertThat(entryPoint.host()).isEqualTo("localhost");
            assertThat(entryPoint.port()).isEqualTo(8080);
        }
        {
            EndpointKey entryPoint = EndpointKey.make("localhost");
            assertThat(entryPoint.host()).isEqualTo("localhost");
            assertThat(entryPoint.port()).isZero();
        }
    }

}
