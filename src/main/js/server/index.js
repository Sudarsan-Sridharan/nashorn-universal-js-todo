import "core-js/es5";
import "core-js/es6";
import "core-js/es7";
import "core-js/js";
import "./nashorn-console-polyfill";

import { renderTodoApp } from "../todo/index.js";

global.TODO = {
    renderTodoApp
};
