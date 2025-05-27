package com.kajsiebert.sip.openai;

/**
 * Represents the configuration for a given extension, including OpenAI instructions, voice, and
 * initial greeting.
 */
public class ExtensionConfig {
  private final String instructions;
  private final String voice;
  private final String greeting;

  public ExtensionConfig(String instructions, String voice, String greeting) {
    this.instructions = instructions;
    this.voice = voice;
    this.greeting = greeting;
  }

  public String getInstructions() {
    return instructions;
  }

  public String getVoice() {
    return voice;
  }

  public String getGreeting() {
    return greeting;
  }
}
