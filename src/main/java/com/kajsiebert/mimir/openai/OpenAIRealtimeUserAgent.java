package com.kajsiebert.mimir.openai;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import org.mjsip.config.OptionParser;
import org.mjsip.media.MediaDesc;
import org.mjsip.pool.PortConfig;
import org.mjsip.pool.PortPool;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.message.SipMethods;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipId;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.sip.provider.SipStack;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;
import org.mjsip.ua.RegisteringMultipleUAS;
import org.mjsip.ua.ServiceConfig;
import org.mjsip.ua.ServiceOptions;
import org.mjsip.ua.UAConfig;
import org.mjsip.ua.UserAgent;
import org.mjsip.ua.UserAgentListener;
import org.mjsip.ua.UserAgentListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kajsiebert.mimir.openai.util.OptionsListener;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

public class OpenAIRealtimeUserAgent extends RegisteringMultipleUAS {
  private static final Logger LOG = LoggerFactory.getLogger(OpenAIRealtimeUserAgent.class);

  private static final OptionsListener optionsListener = new OptionsListener();

  /** Creates a {@link OpenAIRealtimeUserAgent} service. */
  private final Vertx vertx;

  private final ExtensionConfigManager extConfigManager;

  public OpenAIRealtimeUserAgent(
      SipProvider sip_provider,
      UAConfig uaConfig,
      PortPool portPool,
      boolean force_reverse_route,
      ServiceOptions serviceConfig,
      Vertx vertx,
      ExtensionConfigManager extConfigManager) {

    super(sip_provider, portPool, uaConfig, serviceConfig);
    sip_provider.addSelectiveListener(SipId.createMethodId(SipMethods.MESSAGE), this);
    sip_provider.addSelectiveListener(SipId.createMethodId(SipMethods.OPTIONS), optionsListener);
    this.vertx = vertx;
    this.extConfigManager = extConfigManager;
  }

  @Override
  protected UserAgentListener createCallHandler(SipMessage msg) {
    String lastExtensionCalled = msg.getHeader("X-Called-Extension").getValue();
    final ExtensionConfig cfg = extConfigManager.getConfig(lastExtensionCalled);

    LOG.debug("Creating call handler for extension: {}", lastExtensionCalled);

    return new UserAgentListenerAdapter() {
      @Override
      public void onUaIncomingCall(
          UserAgent ua, NameAddress callee, NameAddress caller, MediaDesc[] media_descs) {
        final OpenAICallController streamer =
            new OpenAICallController(OpenAIRealtimeUserAgent.this.vertx, ua, cfg);
        streamer.awaitCallHandled(30);
      }
    };
  }

  /** The main method. */
  public static void main(String[] args) {
    System.out.println("Echo " + SipStack.version);

    SipConfig sipConfig = new SipConfig();
    UAConfig uaConfig = new UAConfig();
    SchedulerConfig schedulerConfig = new SchedulerConfig();
    PortConfig portConfig = new PortConfig();
    ServiceConfig serviceConfig = new ServiceConfig();

    OptionParser.parseOptions(
        args, ".mjsip-ua", sipConfig, uaConfig, schedulerConfig, portConfig, serviceConfig);

    sipConfig.normalize();
    uaConfig.normalize(sipConfig);

    // Load extension configuration or fallback to default
    ExtensionConfigManager extConfigManager;
    try {
      extConfigManager = ExtensionConfigManager.load("extensions.yml");
    } catch (IOException e) {
      System.err.println("Error loading extension configuration: " + e.getMessage());
      System.exit(1);
      return;
    }
    // Initialize a single shared Vert.x instance for all sessions with optimized configuration
    VertxOptions vertxOptions =
        new VertxOptions()
            .setEventLoopPoolSize(4) // Dedicated event loops
            .setWorkerPoolSize(10) // Adequate worker threads for blocking operations
            .setInternalBlockingPoolSize(10)
            .setMaxEventLoopExecuteTime(
                2000) // Allow slightly longer event loop execution for RTP timing
            .setMaxWorkerExecuteTime(60000)
            .setWarningExceptionTime(5000);

    Vertx vertx = Vertx.vertx(vertxOptions);
    OpenAIRealtimeUserAgent userAgent =
        new OpenAIRealtimeUserAgent(
            new SipProvider(sipConfig, new ConfiguredScheduler(schedulerConfig)),
            uaConfig,
            portConfig.createPool(),
            false,
            serviceConfig,
            vertx,
            extConfigManager);

    // Use CountDownLatch for clean shutdown coordination
    CountDownLatch shutdownLatch = new CountDownLatch(1);

    // Register shutdown hook for graceful cleanup on SIGTERM/SIGINT
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  LOG.info("Shutdown signal received, cleaning up...");
                  try {
                    // Close Vert.x gracefully
                    vertx
                        .close()
                        .onComplete(
                            ar -> {
                              if (ar.succeeded()) {
                                LOG.info("Vert.x closed successfully");
                              } else {
                                LOG.error("Error closing Vert.x", ar.cause());
                              }
                              shutdownLatch.countDown();
                            });

                    // Wait for cleanup with timeout
                    if (!shutdownLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                      LOG.warn("Shutdown timeout exceeded, forcing exit");
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.warn("Shutdown interrupted");
                  }
                }));

    System.out.println("OpenAI Realtime User Agent started. Press CTRL+C to stop.");

    try {
      // Keep main thread alive until shutdown signal
      shutdownLatch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.info("Main thread interrupted");
    }

    LOG.info("Application stopped");
  }
}
