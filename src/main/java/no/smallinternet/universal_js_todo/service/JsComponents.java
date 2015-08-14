package no.smallinternet.universal_js_todo.service;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.servlet.ServletContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import renderer.JsModel;
import renderer.JsRenderer;
import renderer.NashornRenderer;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

@Service
public class JsComponents implements TodoComponents<JsModel> {

    public interface Renderer extends TodoComponents<String> {}

    private final JsRenderer<Renderer> jsRenderer;

    private static final Logger LOG = LoggerFactory.getLogger(JsComponents.class);
    private static final String[] JS_FILES = {"/dist/todo-server.js"};
    private static final String jsNamespace = "TODO";

    @Autowired
    public JsComponents(ServletContext context) {
        final boolean isReloadingEnabled = true;

        LOG.info("Initializing JsComponents service...");
        /*
        * - initialize JsRenderer. Pass in stuff it needs: the interface to implement, the name of the global object that contains the
        * functions that implement the interface, ...*/

        List<File> jsFiles = getJsFiles(context::getRealPath);

        NashornRenderer.Builder<Renderer> builder = new NashornRenderer.Builder<>(Renderer.class, jsFiles).jsNamespace(jsNamespace);

        if (isReloadingEnabled) {
            builder.enableReloading();
        }
        this.jsRenderer = builder.build();
    }

    private static List<File> getJsFiles(Function<String, String> pathFn) {
        return stream(JS_FILES)
                .map(pathFn)
                .map(File::new)
                .collect(toList());
    }

    @Override
    public JsModel renderTodoApp(Map<String, Object> data, String location) {
        return JsModel.createModelWithState("todoApp", jsNamespace, data, location,
                jsRenderer.getProxyObject().renderTodoApp(data, location));
    }
}