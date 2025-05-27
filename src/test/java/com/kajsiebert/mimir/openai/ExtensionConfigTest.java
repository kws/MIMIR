package com.kajsiebert.mimir.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ExtensionConfig Tests")
class ExtensionConfigTest {

  @Test
  @DisplayName("Should create extension config with all parameters")
  void shouldCreateExtensionConfigWithAllParameters() {
    String instructions = "You are a helpful assistant.";
    String voice = "alloy";
    String greeting = "Hello there!";

    ExtensionConfig config = new ExtensionConfig(instructions, voice, greeting);

    assertThat(config.getInstructions()).isEqualTo(instructions);
    assertThat(config.getVoice()).isEqualTo(voice);
    assertThat(config.getGreeting()).isEqualTo(greeting);
  }

  @Test
  @DisplayName("Should handle null values gracefully")
  void shouldHandleNullValuesGracefully() {
    ExtensionConfig config = new ExtensionConfig(null, null, null);

    assertThat(config.getInstructions()).isNull();
    assertThat(config.getVoice()).isNull();
    assertThat(config.getGreeting()).isNull();
  }

  @Test
  @DisplayName("Should handle empty strings")
  void shouldHandleEmptyStrings() {
    ExtensionConfig config = new ExtensionConfig("", "", "");

    assertThat(config.getInstructions()).isEmpty();
    assertThat(config.getVoice()).isEmpty();
    assertThat(config.getGreeting()).isEmpty();
  }

  @Test
  @DisplayName("Should preserve original string values")
  void shouldPreserveOriginalStringValues() {
    String instructions = "Multi-line\ninstructions with\nspecial characters: áéíóú!@#$%";
    String voice = "echo-premium";
    String greeting = "¡Hola! ¿Cómo estás? I'm here to help.";

    ExtensionConfig config = new ExtensionConfig(instructions, voice, greeting);

    assertThat(config.getInstructions()).isEqualTo(instructions);
    assertThat(config.getVoice()).isEqualTo(voice);
    assertThat(config.getGreeting()).isEqualTo(greeting);
  }
}
