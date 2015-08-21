package no.smallinternet.universal_js_todo.web;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import no.smallinternet.universal_js_todo.service.JsComponents;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@Controller
public class TodoController {

    private final JsComponents js;

    @Autowired
    public TodoController(JsComponents js) {
        this.js = js;
    }

    @RequestMapping(value = "/", method = RequestMethod.GET)
    String index(Model model, HttpServletRequest request) {
        // get data from data layer
        final Map<String, Object> data = new HashMap<>();
        data.put("yo", "mama");
        // get location from request object
        final String location = "";
        final String path = request.getRequestURI();
        final String queryString = request.getQueryString();
        model.addAttribute("todo", js.renderTodoApp(toJson(data), path, queryString));
        return "index";
    }

    private static String toJson(Object o) {
        final ObjectMapper objectMapper = new ObjectMapper();
        try {
            return objectMapper.writeValueAsString(o);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

}
