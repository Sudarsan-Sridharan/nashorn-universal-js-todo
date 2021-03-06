package renderer;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JsReloader<T> implements Supplier<T> {
    private static final Logger LOG = LoggerFactory.getLogger(JsReloader.class);
    private final Class<T> clazz;
    private final Supplier<T> supplier;
    private final Collection<File> jsFiles;

    public JsReloader(Class<T> clazz, Supplier<T> supplier, Collection<File> jsFiles) {
        this.clazz = clazz;
        this.supplier = supplier;
        this.jsFiles = jsFiles;
    }

    @SuppressWarnings("unchecked")
    @Override
    public T get() {
        final InvocationHandler handler = new ReloaderInvocationHandler(supplier, jsFiles);
        return (T) Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz}, handler);
    }


    private static class ReloaderInvocationHandler implements InvocationHandler {
        private final Supplier<?> supplier;
        private final Collection<File> jsFiles;
        private long lastAttempt = 0;
        private Object current;

        public ReloaderInvocationHandler(Supplier<?> supplier, Collection<File> jsFiles) {
            this.supplier = supplier;
            this.jsFiles = jsFiles;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws IllegalAccessException, InvocationTargetException {
            if (current == null || hasFilesChanged()) {
                lastAttempt = System.currentTimeMillis();
                current = getCurrent();
            }
            try {
                return method.invoke(current, args);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) e.getCause();
                }
                throw e;
            }
        }

        private Object getCurrent() {
            try {
                return supplier.get();
            } catch (RuntimeException e) {
                LOG.warn("Could not load JS", e);
                if (current == null) {
                    throw e;
                }
                return current;
            }
        }

        private boolean hasFilesChanged() {
            final long newLastModified = jsFiles.stream()
                    .filter((f) -> f.length() > 0)
                    .mapToLong(File::lastModified)
                    .max()
                    .orElse(0);
            return (newLastModified > lastAttempt);
        }
    }
}
