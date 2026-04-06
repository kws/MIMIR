# MIMIR 🧙‍♂️ - Talk to the Greatest Minds in History

> *"In the halls of Valhalla, wisdom echoes through time..."*

**MIMIR** is your magical bridge to the past - a SIP client that lets you have real-time voice conversations with history's greatest scientists! Ever wanted to debate relativity with Einstein, discuss evolution with Darwin, or look for missing cats with Schrödinger? Now you can!

## 🧬 The Origin Story

This project is named after **Mímir** from Norse mythology - a figure renowned for his knowledge and wisdom. According to legend, after Mímir was beheaded during the Æsir–Vanir War, the god Odin carried around his head, and it would recite secret knowledge and counsel to him.

Just like Odin consulting Mímir's wise head, you can now consult with the brilliant minds of history through the magic of AI and voice technology!

The project is a concept by [Kaj Siebert](https://k-si.com/) and is part of an interactive art installation for [The Big Bang Collective](https://thebigbangcollective.com/).

## 🔬 What Does It Do?

MIMIR creates a bridge between:

- **Your Asterisk PBX** (the telephone system)
- **OpenAI's Real-time API** (the AI brain)
- **Famous Scientists** (configured as different extensions)

Simply dial an extension, and you'll be connected to have a live voice conversation with your chosen historical figure. Each extension can be configured to embody a different scientist with their unique personality, knowledge, and speaking style.

## 🛠️ How to Build and Run

### Prerequisites

- **Java 11** or higher
- **Maven** 3.6+
- **OpenAI API Key**
- **Asterisk PBX** (or compatible SIP server)

### Build the Project

```bash
# Clone and build
git clone <your-repo-url>
cd mjsip-test
mvn clean package

# This creates a fat JAR with all dependencies
# Output: target/sip-client-1.0-SNAPSHOT-jar-with-dependencies.jar
```

### Setup Your Environment

```bash
# Set your OpenAI API key
export OPENAI_API_KEY="your-openai-api-key-here"
```

### Configuration File

Create a `.mjsip-ua` file with your SIP settings:

```ini
# Minimum required configuration
registrar=sip.your-provider.com
sip-user=your-username
auth-user=your-auth-username  
auth-passwd=your-password
```

### Run the Application

```bash
# Basic usage
java -jar target/sip-client-1.0-SNAPSHOT-jar-with-dependencies.jar

# With custom configuration file
java -jar target/sip-client-1.0-SNAPSHOT-jar-with-dependencies.jar -f your-config.mjsip-ua

# See all available options
java -jar target/sip-client-1.0-SNAPSHOT-jar-with-dependencies.jar -h
```

## 📞 Making Calls

### Asterisk Configuration

To route different extensions to different scientists, you need to configure Asterisk to add custom headers to SIP messages. Add this to your Asterisk dialplan (`extensions.conf`):

```asterisk
; Subroutine to add custom SIP headers
[add-header]
exten => s,1,Set(PJSIP_HEADER(add,X-Called-Extension)=${ARG1})
same  => n,Return()

; Route extensions 2001-2099 to different scientists
exten => _20XX,1,NoOp(Setting up call to extension ${EXTEN})
same => n,Dial(PJSIP/mimir-user,,b(add-header^s^1(${EXTEN})))
same => n,Hangup()
```

This configuration:
- Matches any extension from 2001-2099 (`_20XX` pattern)
- Adds a custom SIP header containing the dialed extension number
- Routes the call to your MIMIR SIP user account (`mimir-user`)
- MIMIR uses the header to determine which scientist to connect

Replace `mimir-user` with your actual SIP account name that MIMIR is registered as.

### Making the Call

Once MIMIR is running and registered with your PBX:

1. **Dial an extension** (e.g., 2001, 2002, 2003) from any SIP phone connected to your Asterisk system
2. **Wait for the connection** - MIMIR will answer and establish the AI connection to the appropriate scientist
3. **Start talking!** - Have a real-time voice conversation with your chosen scientist
4. **Enjoy the conversation** - Ask questions, debate theories, or just chat!

## 🎭 Configuring Scientists

You can configure multiple extensions, each representing a different scientist. The system supports customizing:

- **Personality traits** and speaking styles
- **Areas of expertise** and knowledge focus
- **Historical context** and time period awareness
- **Response patterns** and conversational preferences

(Configuration details can be found in the extension configuration files)


## 🧩 New Deployable Service Split

A deployable two-service extraction is now included under `services/`:

- `services/sip-flow-handler`: SIP registration + INVITE policy + call state machine
- `services/media-bridge`: RTP/websocket AI/media runtime with SSE event stream
- `contracts/media-control.openapi.yaml` and `contracts/media-control.proto`: explicit control contracts

See `services/README.md` for run/build steps and extraction anchors.

## 🏗️ Technical Architecture

MIMIR is built on solid foundations:

- **🍃 Java 11** - Modern, reliable platform
- **📡 MJSIP Framework** - Robust SIP protocol implementation  
- **⚡ Vert.x** - High-performance asynchronous processing
- **🎵 G.711 Audio** - Crystal clear voice quality (PCMU at 8000Hz)
- **🤖 OpenAI Real-time API** - State-of-the-art conversational AI
- **🧪 Comprehensive Testing** - 100+ tests ensuring reliability

## 🚀 Advanced Features

- **🎯 High-Performance RTP Timing** - Optimized for real-time audio with minimal jitter
- **🔄 Adaptive Audio Buffering** - Automatically adjusts to network conditions  
- **📊 Performance Monitoring** - Built-in metrics and logging
- **🎛️ Flexible Configuration** - Extensive customization options
- **🧪 Test Coverage** - Robust test suite with 35% coverage and growing

## 🆘 Getting Help

- **Command Line Help**: Run with `-h` flag for all available options
- **Configuration**: Check your `.mjsip-ua` file format and network settings
- **Audio Issues**: Verify your SIP server supports G.711 u-law (PCMU) codec
- **API Issues**: Ensure your `OPENAI_API_KEY` is valid and has sufficient credits

## 🎉 Have Fun!

Remember, this is about having fun while exploring the intersection of telecommunications, AI, and history. Whether you're asking Newton about calculus, chatting with Tesla about electricity, or discussing the universe with Hawking - enjoy these impossible conversations made possible by modern technology!

*"Any sufficiently advanced technology is indistinguishable from magic."* - Arthur C. Clarke

---

**Happy time traveling! 🚀🔬⚡** 