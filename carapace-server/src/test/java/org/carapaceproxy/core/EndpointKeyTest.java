package org.carapaceproxy.core;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import org.junit.Test;

public class EndpointKeyTest {

    @Test
    public void endpointKeyTest() {
        {
            EndpointKey entryPoint = EndpointKey.make("localhost:8080");
            assertThat(entryPoint.host(), is("localhost"));
            assertThat(entryPoint.port(), is(8080));
        }
        {
            EndpointKey entryPoint = EndpointKey.make("localhost");
            assertThat(entryPoint.host(), is("localhost"));
            assertThat(entryPoint.port(), is(0));
        }
    }

}
