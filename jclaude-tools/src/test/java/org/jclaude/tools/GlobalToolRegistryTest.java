package org.jclaude.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GlobalToolRegistryTest {

    @Test
    void global_registry_is_preloaded_with_mvp_tool_specs() {
        GlobalToolRegistry registry = GlobalToolRegistry.global();

        assertThat(registry.specs()).hasSize(13);
        assertThat(registry.find("read_file")).isPresent();
        assertThat(registry.find("nonexistent")).isEmpty();
    }

    @Test
    void set_specs_replaces_active_list() {
        GlobalToolRegistry registry = new GlobalToolRegistry(MvpToolSpecs.mvp_tool_specs());

        registry.set_specs(MvpToolSpecs.mvp_tool_specs().subList(0, 1));

        assertThat(registry.specs()).hasSize(1);
        assertThat(registry.specs().get(0).name()).isEqualTo("read_file");
    }
}
