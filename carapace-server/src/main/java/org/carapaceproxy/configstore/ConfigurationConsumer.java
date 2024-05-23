package org.carapaceproxy.configstore;

import java.util.function.Consumer;

@FunctionalInterface
public interface ConfigurationConsumer extends Consumer<PropertiesConfigurationStore> {
}
