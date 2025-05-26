# SIP Agents Documentation

## Project Structure
```
src/main/java/com/kajsiebert/sip/openai/
├── OpenAIRealtimeUserAgent.java  # Main SIP agent implementation
└── VertxMediaStreamer.java       # Media streaming implementation using Vert.x
```

## Build System
- **Build Tool**: Maven
- **Java Version**: 11
- **Main Class**: `com.kajsiebert.sip.openai.OpenAIRealtimeUserAgent`
- **Build Command**: `mvn clean package`
- **Output**: Creates a fat JAR with dependencies in `target/sip-client-1.0-SNAPSHOT-jar-with-dependencies.jar`

## Dependencies
### Core Dependencies
- **MJSIP Framework** (v2.0.5)
  - `mjsip-ua`: User Agent implementation
  - `mjsip-server`: SIP Server components
- **Vert.x** (v4.4.5)
  - `vertx-core`: Core Vert.x functionality
  - `vertx-web-client`: Web client capabilities

### Supporting Libraries
- **Logging**
  - SLF4J API (v1.7.36)
  - Logback Classic (v1.2.11)
- **Utilities**
  - Args4J (v2.33): Command line parsing
  - Jackson Core (v2.17.1): JSON processing
  - ACodec (v1.0.0): Audio codec support

## OpenAIRealtimeUserAgent

The `OpenAIRealtimeUserAgent` is a specialized SIP (Session Initiation Protocol) user agent that extends the `RegisteringMultipleUAS` class. It's designed to handle real-time audio communication with OpenAI integration.

### Key Features:
- Extends `RegisteringMultipleUAS` for handling multiple user agent sessions
- Supports G.711 u-law audio codec (PCMU) at 8000Hz
- Integrates with Vert.x for asynchronous event handling
- Handles incoming SIP calls with real-time audio streaming

### Technical Details:
- Uses MJSIP framework for SIP protocol implementation
- Implements custom media streaming through `VertxMediaStreamer`
- Supports standard SIP message handling and call management
- Configurable through various configuration objects (SipConfig, UAConfig, etc.)

### Main Components:
1. **Constructor**: Initializes the agent with SIP provider, configuration, and Vert.x instance
2. **Call Handler**: Creates a custom call handler for incoming calls
3. **Media Configuration**: Supports PCMU audio codec with specific parameters
4. **Main Method**: Entry point for running the agent with configuration parsing

### Usage:
The agent can be started by running the main method, which:
1. Parses command-line options
2. Initializes necessary configurations
3. Creates a Vert.x instance
4. Starts the SIP user agent
5. Waits for user input before exit

This agent is particularly useful for scenarios requiring real-time audio communication with OpenAI integration, such as voice-based AI interactions or automated call handling systems.
