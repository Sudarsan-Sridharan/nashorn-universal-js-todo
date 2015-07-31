package service;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import javax.annotation.PreDestroy;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import javax.servlet.ServletContext;

import jdk.nashorn.api.scripting.NashornScriptEngine;
import jdk.nashorn.api.scripting.NashornScriptEngineFactory;
import jdk.nashorn.api.scripting.URLReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static java.lang.ThreadLocal.withInitial;
import static java.util.Arrays.stream;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.toList;
import static javax.script.ScriptContext.ENGINE_SCOPE;
import static service.JsComponentImpl.GLOBAL_LOCATION_NAME;
import static service.JsComponentImpl.GLOBAL_PROPS_NAME;

@Service
public class NashornRenderer implements JsRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(NashornRenderer.class);
    //private static final MetricNameStatsdClient statsd = StatsdClient.from(NashornRenderer.class);
    private static final String[] NASHORN_OPTS = {"--persistent-code-cache", "--optimistic-types"};
    private static final String[] JS_FILES = {
            "/jsdist/nashorn/oppdrag.js"
    };

    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private final AtomicReference<RenderFn> renderFn = new AtomicReference<>((a, b) -> () -> "");
    private final AtomicLong lastModified = new AtomicLong();
    private final List<File> jsFiles;
    private final boolean jsReloadingEnabled;

    @Autowired
    public NashornRenderer(ServletContext context,
                           @Value("${isJsReloadingEnabled}") boolean jsReloadingEnabled) throws IOException {
        this.jsReloadingEnabled = jsReloadingEnabled;
        this.jsFiles = stream(JS_FILES)
                .map(context::getRealPath)
                .map(File::new)
                .collect(toList());
        pool.execute(this::loadJs);
    }

    private void loadJs() {
        try {
            final NashornScriptEngine engine = getEngine();
            do {
                if (filesChanged()) {
                    final ThreadLocal<ScriptContext> localContext = withInitial(() -> initScriptContext(engine));

                    try {
                        localContext.get();
                        renderFn.set(getRenderFn(engine, localContext));
                    } catch (RuntimeException e) {
                        LOG.error("Could not load JS", e);
                    }
                }
                Thread.sleep(200);
            } while (jsReloadingEnabled);
        } catch (InterruptedException e) {
            LOG.info("Nashorn interrupted, will exit.");
        } catch (RuntimeException e) {
            LOG.error("Could not init Nashorn", e);
        }
    }

    private ScriptContext initScriptContext(NashornScriptEngine engine) {
        final String threadName = Thread.currentThread().getName();
        LOG.debug("Initializing Nashorn ({})", threadName);
        final Bindings global = engine.createBindings();
        final ScriptContext context = new SimpleScriptContext();
        context.setBindings(global, ENGINE_SCOPE);
        jsFiles.forEach(f -> load(engine, context, f));
        LOG.debug("Nashorn initialized ({})", threadName);
        return context;
    }

    private static NashornScriptEngine getEngine() {
        final NashornScriptEngineFactory engineFactory = new NashornScriptEngineFactory();
        try {
            return (NashornScriptEngine) engineFactory.getScriptEngine(NASHORN_OPTS);
        } catch (IllegalArgumentException e) {
            return (NashornScriptEngine) engineFactory.getScriptEngine();
        }
    }

    private boolean filesChanged() {
        final Long newLastModified = jsFiles.stream()
                .filter((f) -> f.length() > 0)
                .mapToLong(File::lastModified)
                .max()
                .orElse(0);
        return (newLastModified > lastModified.getAndSet(newLastModified));
    }

    private static void load(NashornScriptEngine engine, ScriptContext context, File f) {
        try {
            LOG.debug("Loading JS file {}", f.getName());
            engine.eval(new URLReader(f.toURI().toURL()), context);
        } catch (IOException | ScriptException e) {
            throw new RuntimeException("Error loading JS file " + f.getName(), e);
        }
    }

    @Override
    public Supplier<String> render(JsComponent component, JsComponentState state) {
        return renderFn.get().apply(component, state);
    }

    private RenderFn getRenderFn(NashornScriptEngine engine, ThreadLocal<ScriptContext> context) {
        return (component, state) -> {
            try {
                final String threadName = Thread.currentThread().getName();
                LOG.debug("Submitting JS for rendering ({})", threadName);
                final Future<String> future = pool.submit(() -> doRender(component, state, engine, context.get()));
                return () -> getRendered(future, component);
            } catch (RejectedExecutionException e) {
                LOG.warn("Could not submit JS for rendering: {}", e.getMessage());
            }
            return () -> "";
        };
    }

    private static String doRender(JsComponent component, JsComponentState state, NashornScriptEngine engine,
                                   ScriptContext context)
            throws ScriptException {
        long startTime = System.nanoTime();
        try {
            LOG.debug("putting props");
            context.getBindings(ENGINE_SCOPE).put(GLOBAL_PROPS_NAME, Util.toJson(state.getData()));
            LOG.debug("putting location");
            context.getBindings(ENGINE_SCOPE).put(GLOBAL_LOCATION_NAME, state.getLocation());
            LOG.debug("doing eval");
            Object html = engine.eval(component.getRenderStatement(), context);
            LOG.debug("eval finished");
            logTiming(component, System.nanoTime() - startTime, "rendered");
            return String.valueOf(html);
        } catch (ScriptException e) {
            logTiming(component, System.nanoTime() - startTime, "failed");
            LOG.error("Could not render JS", e.getCause());
        }
        return "";
    }

    private static void logTiming(JsComponent component, long elapsedTime, String status) {
        //statsd.time(getMetricName(component.getId(), status), elapsedTime, NANOSECONDS);
        //LOG.debug("{}: {} ms", getMetricName(component.getId(), status).getAspect(), NANOSECONDS.toMillis(elapsedTime));
        LOG.debug("{} {}: {} ms", component.getId(), status, NANOSECONDS.toMillis(elapsedTime));
    }

//    private static MetricName getMetricName(String id, String status) {
//        return MetricName.of(String.join(".", "oppdrag-web", "nashorn", id, status));
//    }

    private static String getRendered(Future<String> future, JsComponent component) {
        try {
            return future.get(50, MILLISECONDS);
        } catch (InterruptedException e) {
            LOG.error("Rendering interrupted", e);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOG.error("Could not render JS", e);
        } catch (TimeoutException e) {
            //statsd.increment(getMetricName(component.getId(), "timedout"));
            //LOG.warn(getMetricName(component.getId(), "timedout").getAspect());
            LOG.warn("{} timed out", component.getId());
        }
        return "";
    }

    @PreDestroy
    public void shutdownExecutor() {
        pool.shutdownNow();
    }

    private interface RenderFn extends BiFunction<JsComponent, JsComponentState, Supplier<String>> {}
}